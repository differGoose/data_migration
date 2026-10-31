package com.datamigration.execution;

import com.datamigration.api.*;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.audit.*;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.dialect.DatabaseDialect;
import com.datamigration.jdbc.*;
import com.datamigration.model.*;
import com.datamigration.sql.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synchronous facade, bounded table/batch concurrency, durable transaction-local checkpoints. */
public final class JdbcMigrationEngine implements MigrationEngine, AutoCloseable {
    private final ConnectionAccess connections;
    private final int sourceCapacity;
    private final int targetCapacity;
    private static final Semaphore EXECUTION = new Semaphore(1);
    private final JdbcRows rows;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final ThreadLocal<Boolean> engineWorker = ThreadLocal.withInitial(() -> false);
    private Thread caller;
    private volatile boolean closed;
    private MigrationObserver observer = MigrationObserver.NONE;

    public JdbcMigrationEngine(ConnectionAccess connections, int sourceCapacity, int targetCapacity,
                               ValueConverter converter) {
        this.connections = connections; this.sourceCapacity = sourceCapacity;
        this.targetCapacity = targetCapacity;
        this.rows = new JdbcRows(converter);
    }

    @Override public MigrationResult execute(MigrationPlan plan) { return execute(plan, MigrationObserver.NONE); }
    public MigrationResult execute(MigrationPlan plan, MigrationObserver observer) {
        try (var invocation = MigrationLog.invocation()) {
            MigrationLog.job(plan.jobId());
            MigrationLog.stage("MIGRATION_START", "-", "data-migration");
            MigrationLog.info("Migration execution started", "tables=" + plan.tables().size());
            try { return executeScoped(plan, observer); }
            catch (RuntimeException failure) {
                if (!(failure instanceof MigrationException)) MigrationLog.error("Migration execution failed", failure);
                throw failure;
            }
        }
    }
    private MigrationResult executeScoped(MigrationPlan plan, MigrationObserver observer) {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Migration engine is closed");
            if (!running.compareAndSet(false, true)) throw new IllegalStateException("A migration is already running");
            if (!EXECUTION.tryAcquire()) {
                running.set(false);
                throw new IllegalStateException("A migration is already running in this JVM");
            }
            caller = Thread.currentThread();
            this.observer = observer;
        }
        Instant start = Instant.now();
        try {
            MigrationLog.stage("CAPACITY_CHECK", "-", "data-migration.strategy.max-concurrent-tables");
            validateBudget(plan);
            List<TableResult> results = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(plan.strategy().maxConcurrentTables(), plan.tables().size()), workerFactory());
            try (var executorScope = new ExecutorScope(executor)) {
                CompletionService<TableResult> completion = new ExecutorCompletionService<>(executor);
                Iterator<TableTask> tasks = plan.tables().iterator();
                int active = 0;
                while (tasks.hasNext() && active < plan.strategy().maxConcurrentTables()) {
                    TableTask task = tasks.next();
                    completion.submit(MigrationLog.wrap(() -> table(plan, task))); active++;
                }
                while (active > 0) {
                    try {
                        results.add(completion.take().get()); active--;
                    } catch (InterruptedException exception) {
                        executor.shutdownNow(); Thread.currentThread().interrupt();
                        throw new IllegalStateException("Migration interrupted", exception);
                    } catch (ExecutionException exception) {
                        throw new IllegalStateException("Unexpected migration worker failure", exception.getCause());
                    }
                    if (tasks.hasNext() && !closed) {
                        TableTask task = tasks.next();
                        completion.submit(MigrationLog.wrap(() -> table(plan, task))); active++;
                    }
                }
            }
            if (closed) throw new IllegalStateException("Migration stopped before all tasks completed");
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < plan.tables().size(); i++) order.put(plan.tables().get(i).name(), i);
            results.sort(Comparator.comparingInt(result -> order.get(result.taskName())));
            MigrationResult result = new MigrationResult(MigrationLog.runId(), start, Instant.now(), results);
            MigrationLog.stage("MIGRATION_COMPLETE", "-", "-");
            MigrationLog.info("Migration execution completed", "tables=" + results.size() + " failedTables="
                    + results.stream().filter(t -> t.status() != TableResult.Status.SUCCEEDED).count()
                    + " elapsedMs=" + java.time.Duration.between(start, result.finishedAt()).toMillis());
            if (results.stream().anyMatch(t -> t.status() != TableResult.Status.SUCCEEDED)) throw new MigrationException(result);
            return result;
        } finally {
            synchronized (lifecycle) {
                running.set(false); caller = null; this.observer = MigrationObserver.NONE; EXECUTION.release(); lifecycle.notifyAll();
            }
        }
    }

    public void validateBudget(MigrationPlan plan) {
        if (!plan.strategy().auditEnabled()) throw new IllegalArgumentException("Audit is required for durable recovery");
        if (plan.tables().isEmpty()) throw new IllegalArgumentException("No enabled table tasks");
        if (plan.jobId() == null || !plan.jobId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}"))
            throw new IllegalArgumentException("Invalid job-id");
        int parallel = plan.strategy().maxConcurrentTables();
        // One coordinator plus worker connections; keyless tables use one source and two targets.
        int sources = plan.tables().stream().mapToInt(t -> t.keyed() ? t.workerCount() + 1 : 1)
                .boxed().sorted(Comparator.reverseOrder()).limit(parallel).mapToInt(Integer::intValue).sum();
        int targets = plan.tables().stream().mapToInt(t -> t.workerCount() + 1)
                .boxed().sorted(Comparator.reverseOrder()).limit(parallel).mapToInt(Integer::intValue).sum();
        MigrationLog.info("Migration connection budget", "requiredSource=" + sources + " declaredSource=" + sourceCapacity
                + " requiredTarget=" + targets + " declaredTarget=" + targetCapacity);
        if (sources > sourceCapacity || targets > targetCapacity)
            throw new IllegalArgumentException("Insufficient pool capacity; source requires " + sources + ", target requires " + targets);
        Set<String> targetsSeen = new HashSet<>();
        for (TableTask task : plan.tables()) {
            if (!task.keyed() && task.workerCount() != 1) throw new IllegalArgumentException("Keyless tables require one worker");
            if (!targetsSeen.add(task.targetTable().toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate target table");
        }
    }

    private TableResult table(MigrationPlan plan, TableTask task) {
        try (var diagnostic = MigrationLog.table(plan.jobId(), task.name(), plan.tables().indexOf(task))) {
            MigrationLog.info("Table migration started", "targetTable=" + MigrationLog.safe(task.targetTable())
                    + " workers=" + task.workerCount() + " batchSize=" + task.batchSize() + " mode=" + task.mode());
            return tableScoped(plan, task);
        }
    }
    private TableResult tableScoped(MigrationPlan plan, TableTask task) {
        Instant start = Instant.now();
        observer.tableStarted(task.name());
        CheckpointStore store = new CheckpointStore(plan.strategy().auditTableName(), plan.jobId(), task.name());
        boolean finalizedData = false;
        boolean checkpointValidated = false;
        try (Connection control = targetConnection();
             Connection source = sourceConnection()) {
            try {
                MigrationLog.stage("AUDIT_CHECK", "target", "data-migration.strategy.audit.table-name");
                DatabaseDialect dialect = DatabaseDialect.target(control);
                MigrationLog.info("Checking audit schema", "");
                store.check(control);
                MigrationLog.info("Audit schema check succeeded", "");
                String query = SqlSupport.query(task);
                String canonicalTarget = dialect.canonicalTable(control, task.targetTable());
                MigrationLog.stage("CHECKPOINT_READ", "target", "data-migration.strategy.audit.table-name");
                Checkpoint summary = store.find(control, 0, false);
                checkpointValidated = true;
                observer.restored(task.name(), summary);
                if (summary != null && summary.succeeded()) {
                    MigrationLog.info("Table already succeeded; reusing audited result", "");
                    return result(task, start, summary, TableResult.Status.SUCCEEDED, null);
                }
                if (summary != null) MigrationLog.info("Resuming unfinished table", "dataComplete=" + summary.dataComplete());
                List<String> keys = preflight(source, control, query, task);
                MigrationLog.info("Table preflight succeeded", "");
                MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name");
                if (summary == null) {
                    store.create(control, 0, canonicalTarget, null, null, 0);
                    MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                }
                summary = store.find(control, 0, false);
                finalizedData = summary.dataComplete();
                if (!finalizedData) {
                    MigrationLog.stage("SOURCE_COUNT", "source", "select-sql");
                    MigrationLog.info("Counting source rows", "");
                    long total = count(source, query);
                    observer.counted(task.name(), total);
                    MigrationLog.info("Source row count checked", "rows=" + total);
                    // Keep preparation and optional DELETE in the summary transaction.
                    summary = store.find(control, 0, true);
                    if (summary.prepared() && total != summary.total()) throw new IllegalArgumentException("Source row count changed");
                    if (task.keyed()) {
                        if (!summary.prepared()) {
                            if (task.mode() == TableTask.Mode.CLEAR_AND_REIMPORT) clear(control, task);
                            store.prepared(control, total);
                        }
                        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                        MigrationLog.stage("BATCH_EXECUTION", "-", "batch-size");
                        MigrationLog.info("Table data transfer started", "");
                        keyed(plan, task, query, keys, source, control, store, canonicalTarget);
                        store.find(control, 0, true);
                        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name");
                        store.reconcile(control);
                        Checkpoint done = store.find(control, 0, false);
                        if (done.committed() + done.skipped() != total)
                            throw new SQLException("Planned count differs from source count", "P0004");
                        store.dataComplete(control);
                        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                    } else {
                        source.close(); // Reuse the source pool slot for the single keyless worker.
                        if (store.find(control, 1, false) == null) {
                            store.create(control, 1, canonicalTarget, null, null, total);
                            store.prepared(control, total);
                        }
                        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                        MigrationLog.info("Keyless whole-table data transfer started", "");
                        transfer(plan, task, query, keys, store.find(control, 1, false), store, control);
                        store.find(control, 0, true);
                        store.dataComplete(control);
                        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                    }
                    finalizedData = true;
                    MigrationLog.info("Table data committed", "");
                }
                observer.finalizing(task.name());
                MigrationLog.stage("SEQUENCE_FINALIZE", "target", "sequence");
                store.find(control, 0, true);
                MigrationLog.info("Sequence finalization started", "enabled=" + task.sequence().enabled());
                dialect.calibrate(control, task.targetTable(), task.sequence());
                MigrationLog.info("Sequence finalization completed", "enabled=" + task.sequence().enabled());
                MigrationLog.stage("AUDIT_FINALIZE", "target", "data-migration.strategy.audit.table-name");
                // H2 ALTER SEQUENCE and PostgreSQL setval are not rollbackable, but neither
                // commits this transaction. Retain the summary lock through the final status.
                store.state(control, 0, "SUCCEEDED");
                MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
                return result(task, start, store.find(control, 0, false), TableResult.Status.SUCCEEDED, null);
            } catch (Exception failure) { MigrationLog.remember(failure); throw failure; }
        } catch (Exception exception) {
            MigrationLog.error("Table migration failed", exception);
            String state = exception instanceof CommitUnknownException ? "COMMIT_UNKNOWN" :
                    finalizedData ? "FINALIZATION_FAILED" : "FAILED";
            String error = exception instanceof IllegalArgumentException ? "INVALID_CONFIGURATION_OR_MAPPING" : SqlErrors.summary(exception);
            Checkpoint summary = null;
            MigrationLog.stage("AUDIT_FAILURE_REPORT", "target", "data-migration.strategy.audit.table-name");
            try (Connection report = targetConnection()) {
                summary = store.find(report, 0, false);
                if (checkpointValidated && summary != null) {
                    summary = store.find(report, 0, true);
                    if (!summary.succeeded()) { store.failure(report, 0, state, error); report.commit(); }
                }
            } catch (Exception reportFailure) {
                MigrationLog.secondary("Could not persist table failure; original error retained", reportFailure);
            }
            return result(task, start, summary, TableResult.Status.valueOf(state), error);
        }
    }

    private TableResult result(TableTask task, Instant start, Checkpoint checkpoint, TableResult.Status status, String error) {
        try (var completion = MigrationLog.phase("TABLE_COMPLETE", "-", "-")) {
            MigrationLog.info("Table migration completed", "status=" + status + " committedRows="
                    + (checkpoint == null ? 0 : checkpoint.committed()) + " skippedRows=" + (checkpoint == null ? 0 : checkpoint.skipped())
                    + " elapsedMs=" + java.time.Duration.between(start, Instant.now()).toMillis());
        }
        var result = new TableResult(task.name(), start, Instant.now(), checkpoint == null ? 0 : checkpoint.committed(),
                checkpoint == null ? 0 : checkpoint.skipped(), checkpoint == null ? 0 : checkpoint.retries(), status, error);
        observer.finished(result);
        return result;
    }

    private List<String> preflight(Connection source, Connection target, String query, TableTask task) throws SQLException {
        List<String> keys;
        MigrationLog.stage("SOURCE_PREFLIGHT", "source", "select-sql");
        MigrationLog.info("Source query preflight started", "");
        try (var statement = source.prepareStatement("SELECT * FROM (" + query + ") migration_source WHERE 1=0");
             var result = statement.executeQuery()) {
            JdbcRows.columns(result.getMetaData(), task.fields());
            MigrationLog.stage("KEY_PREFLIGHT", "source", "primary-key");
            keys = SqlSupport.resolveKeys(result.getMetaData(), task.primaryKey());
        }
        MigrationLog.stage("TARGET_PREFLIGHT", "target", "insert-sql");
        MigrationLog.info("Target INSERT preflight started", "");
        try (var insert = prepareInsert(target, task)) {
            MigrationLog.stage("TARGET_PREFLIGHT", "target", "insert-sql");
            if (insert.getParameterMetaData().getParameterCount() != task.fields().size())
                throw new IllegalArgumentException("INSERT parameter metadata differs from fields");
        }
        if (task.keyed()) {
            MigrationLog.stage("KEY_PREFLIGHT", "source", "primary-key");
            MigrationLog.info("Primary-key preflight started", "");
            String keyList = String.join(",", keys);
            String nulls = String.join(" OR ", keys.stream().map(k -> k + " IS NULL").toList());
            try (var statement = source.prepareStatement("SELECT " + keyList + " FROM (" + query +
                    ") migration_source WHERE " + nulls)) {
                statement.setMaxRows(1);
                try (var result = statement.executeQuery()) {
                    if (result.next()) throw new IllegalArgumentException("Primary-key contains nulls");
                }
            }
            try (var statement = source.prepareStatement("SELECT " + keyList + " FROM (" + query +
                    ") migration_source GROUP BY " + keyList + " HAVING COUNT(*)>1")) {
                statement.setMaxRows(1);
                try (var result = statement.executeQuery()) {
                    if (result.next()) throw new IllegalArgumentException("Primary-key is not unique in SELECT results");
                }
            }
        }
        return keys;
    }

    private long count(Connection source, String query) throws SQLException {
        try (var statement = source.prepareStatement("SELECT COUNT(*) FROM (" + query + ") migration_source");
             var result = statement.executeQuery()) {
            result.next(); return result.getLong(1);
        }
    }

    private void keyed(MigrationPlan plan, TableTask task, String query, List<String> keys, Connection source,
                       Connection control, CheckpointStore store, String target) throws Exception {
        int window = Math.multiplyExact(task.workerCount(), 2);
        ExecutorService workers = Executors.newFixedThreadPool(task.workerCount(), workerFactory());
        try (var workerScope = new ExecutorScope(workers)) {
            while (true) {
                checkRunning();
                List<Checkpoint> pending = store.pending(control, window);
                if (pending.isEmpty()) {
                    if (store.find(control, 0, false).planningComplete()) {
                        MigrationLog.debug("All keyed ranges completed", ""); break;
                    }
                    planWindow(task, query, keys, source, control, store, window, target);
                    pending = store.pending(control, window);
                    if (pending.isEmpty()) continue;
                }
                List<MigrationLog.Pending<Void>> futures = new ArrayList<>();
                AtomicBoolean failed = new AtomicBoolean();
                for (Checkpoint batch : pending) {
                    futures.add(MigrationLog.submit(workers, () -> {
                        if (failed.get()) return null; // Leave queued work durably pending for recovery.
                        try { transfer(plan, task, query, keys, batch, store, null); }
                        catch (Exception exception) { failed.set(true); throw exception; }
                        return null;
                    }));
                }
                Exception failure = null;
                for (MigrationLog.Pending<Void> future : futures) {
                    try { future.get(); }
                    catch (ExecutionException exception) {
                        if (failure == null || exception.getCause() instanceof CommitUnknownException)
                            failure = exception.getCause() instanceof Exception e ? e : exception;
                    } catch (InterruptedException exception) {
                        workers.shutdownNow(); Thread.currentThread().interrupt(); throw exception;
                    }
                }
                if (failure != null) throw failure;
            }
        }
    }

    private void planWindow(TableTask task, String query, List<String> keys, Connection source, Connection control,
                            CheckpointStore store, int window, String target) throws SQLException {
        MigrationLog.stage("RANGE_PLAN", "source", "primary-key");
        MigrationLog.debug("Planning transaction blocks", "window=" + window + " batchSize=" + task.batchSize());
        store.find(control, 0, true);
        Checkpoint last = store.last(control);
        List<KeyCodec.Value> lower = last == null ? null : last.upper();
        long number = last == null ? 1 : last.batch() + 1;
        int maxRows = Math.multiplyExact(window, task.batchSize());
        try (var statement = SqlSupport.range(query, keys, lower, null, true).prepare(source, task.batchSize())) {
            statement.setMaxRows(maxRows);
            try (var result = statement.executeQuery()) {
                long count = 0;
                List<KeyCodec.Value> upper = null;
                int batches = 0;
                while (result.next()) {
                    upper = KeyCodec.read(result, keys.size()); count++;
                    if (count == task.batchSize()) {
                        store.create(control, number++, target, lower, upper, count);
                        lower = upper; count = 0; batches++;
                    }
                }
                if (count > 0) store.create(control, number, target, lower, upper, count);
                if (batches < window) store.planningComplete(control);
            }
        }
        MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name"); control.commit();
    }

    private void transfer(MigrationPlan plan, TableTask task, String query, List<String> keys, Checkpoint batch,
                          CheckpointStore store, Connection progressConnection) throws Exception {
        try (var diagnostic = MigrationLog.batch(batch.batch())) {
            transferScoped(plan, task, query, keys, batch, store, progressConnection);
        }
    }
    private void transferScoped(MigrationPlan plan, TableTask task, String query, List<String> keys, Checkpoint batch,
                          CheckpointStore store, Connection progressConnection) throws Exception {
        MigrationLog.debug("Batch started", "expectedRows=" + batch.expected());
        MigrationStrategy strategy = plan.strategy();
        int retries = 0;
        boolean isolate = false;
        boolean progressReported = false;
        while (true) {
            MigrationLog.resetFailure();
            MigrationLog.stage("BATCH_READ", "source", "select-sql");
            checkRunning();
            boolean committedCall = false;
            boolean rolledBack = true; // A failed acquisition has not started any transaction.
            try (Connection target = targetConnection()) {
                rolledBack = false;
                try {
                    MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name");
                    Checkpoint current = store.find(target, batch.batch(), true);
                    if (current.succeeded()) { target.rollback(); return; }
                    store.state(target, batch.batch(), "RUNNING");
                    if (!task.keyed() && task.mode() == TableTask.Mode.CLEAR_AND_REIMPORT) clear(target, task);
                    JdbcRows.Counts counts;
                    MigrationLog.stage("BATCH_READ", "source", "select-sql");
                    try (Connection source = sourceConnection();
                         var read = SqlSupport.range(query, keys, batch.lower(), batch.upper(), false).prepare(source, task.batchSize());
                         var result = read.executeQuery();
                         var insert = prepareInsert(target, task)) {
                        insert.setQueryTimeout(60);
                        counts = rows.copy(result, insert, task, strategy, isolate, processed -> {
                            if (progressConnection != null) {
                                MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name");
                                store.progress(progressConnection, processed); progressConnection.commit();
                            }
                        });
                    }
                    MigrationLog.stage("SOURCE_COUNT", "source", "select-sql");
                    if (counts.read() != batch.expected()) throw new SQLException("Source batch changed", "P0004");
                    checkRunning();
                    MigrationLog.stage("AUDIT_UPDATE", "target", "data-migration.strategy.audit.table-name");
                    store.complete(target, batch.batch(), counts.written(), counts.skipped(), task.keyed() ? strategy.skipLimit() : 0);
                    committedCall = true;
                    MigrationLog.stage("BATCH_COMMIT", "target", "-");
                    target.commit();
                    observer.committed(task.name(), batch.batch(), counts.written(), counts.skipped());
                    progressReported = true;
                    MigrationLog.debug("Batch committed", "committedRows=" + counts.written() + " skippedRows=" + counts.skipped());
                    return;
                } catch (Exception exception) {
                    MigrationLog.remember(exception);
                    if (!committedCall) {
                        try { target.rollback(); rolledBack = true; }
                        catch (SQLException rollbackFailure) { MigrationLog.secondary("Batch rollback failed", rollbackFailure); }
                    }
                    throw exception;
                }
            } catch (Exception exception) {
                MigrationLog.remember(exception);
                if (committedCall) {
                    MigrationLog.warn("Commit outcome requires verification", MigrationLog.failureDetails());
                    MigrationLog.stage("COMMIT_VERIFY", "target", "data-migration.strategy.audit.table-name");
                    try (Connection verify = targetConnection()) {
                        // Lock waits for the original transaction to finish, not just a stale MVCC read.
                        Checkpoint current = store.find(verify, batch.batch(), true);
                        boolean success = current.succeeded();
                        verify.rollback();
                        if (success) {
                            if (!progressReported) observer.committed(task.name(), batch.batch(), current.committed(), current.skipped());
                            MigrationLog.info("Batch commit verified", ""); return; }
                        rolledBack = true;
                    } catch (Exception unresolved) {
                        MigrationLog.resetFailure(); MigrationLog.remember(unresolved);
                        MigrationLog.error("Batch commit outcome remains unknown", unresolved);
                        throw new CommitUnknownException();
                    }
                }
                if (exception instanceof SQLException sql && rolledBack && task.keyed() && !isolate
                        && strategy.skipLimit() > 0 && SqlErrors.data(sql)) {
                    MigrationLog.warn("Retrying rolled-back batch with row isolation", MigrationLog.failureDetails());
                    isolate = true;
                    continue;
                }
                if (exception instanceof SQLException sql && rolledBack && SqlErrors.transientFailure(sql)
                        && retries < strategy.transientRetryCount()) {
                    retries++;
                    try (Connection report = targetConnection()) {
                        store.attempt(report, batch.batch());
                        store.attempt(report, 0);
                        report.commit();
                    }
                    long base = strategy.retryInitialBackoff().toMillis();
                    long cap = strategy.retryMaxBackoff().toMillis();
                    long wait = base;
                    for (int i = 1; i < retries && wait < cap; i++) wait = wait > cap / 2 ? cap : wait * 2;
                    MigrationLog.warn("Retrying transient batch failure", "attempt=" + retries + " maxRetries="
                            + strategy.transientRetryCount() + " backoffMs=" + Math.min(wait, cap) + " " + MigrationLog.failureDetails());
                    Thread.sleep(Math.min(wait, cap));
                    continue;
                }
                MigrationLog.error("Batch migration failed", exception);
                MigrationLog.stage("AUDIT_FAILURE_REPORT", "target", "data-migration.strategy.audit.table-name");
                try (Connection report = targetConnection()) {
                    store.failure(report, batch.batch(), "FAILED", SqlErrors.summary(exception)); report.commit();
                } catch (SQLException reportFailure) { MigrationLog.secondary("Could not persist batch failure; original error retained", reportFailure); }
                throw exception;
            }
        }
    }

    private void clear(Connection connection, TableTask task) throws SQLException {
        MigrationLog.stage("TARGET_PREPARE", "target", "mode");
        MigrationLog.info("Clearing target for explicit reimport", "");
        try (var statement = CheckpointStore.statement(connection, "DELETE FROM " + SqlSupport.table(task.targetTable()))) {
            statement.executeUpdate();
        }
    }

    private Connection sourceConnection() throws SQLException {
        try (var diagnostic = MigrationLog.phase("SOURCE_CONNECT", "source", "-")) {
            MigrationLog.connecting("source");
            try { var connection = connections.openSourceConnection(); MigrationLog.connected("source"); return connection; }
            catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
        }
    }
    private Connection targetConnection() throws SQLException {
        try (var diagnostic = MigrationLog.phase("TARGET_CONNECT", "target", "-")) {
            MigrationLog.connecting("target");
            try { var connection = connections.openTargetConnection(); MigrationLog.connected("target"); return connection; }
            catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
        }
    }
    private PreparedStatement prepareInsert(Connection target, TableTask task) throws SQLException {
        // Preflight and execution set their own stage; this scope identifies preparation failures.
        try (var diagnostic = MigrationLog.phase("TARGET_PREFLIGHT", "target", "insert-sql")) {
            try { return target.prepareStatement(task.insertSql()); }
            catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
        }
    }

    private void checkRunning() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) throw new SQLException("Migration stopped", "57014");
    }

    public boolean isWorkerThread() { return engineWorker.get(); }

    @Override public void close() {
        boolean interrupted = false;
        synchronized (lifecycle) {
            closed = true;
            if (caller != null) caller.interrupt();
            // A converter may request shutdown from an engine worker; it cannot wait for itself.
            if (caller == Thread.currentThread() || engineWorker.get()) return;
            while (caller != null) {
                try { lifecycle.wait(); }
                catch (InterruptedException exception) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private ThreadFactory workerFactory() {
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return operation -> delegate.newThread(() -> {
            engineWorker.set(true);
            try { operation.run(); }
            finally { engineWorker.remove(); }
        });
    }
}
