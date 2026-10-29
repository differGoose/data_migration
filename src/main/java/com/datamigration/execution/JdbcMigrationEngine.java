package com.datamigration.execution;

import com.datamigration.api.*;
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

    public JdbcMigrationEngine(ConnectionAccess connections, int sourceCapacity, int targetCapacity,
                               ValueConverter converter) {
        this.connections = connections; this.sourceCapacity = sourceCapacity;
        this.targetCapacity = targetCapacity;
        this.rows = new JdbcRows(converter);
    }

    @Override public MigrationResult execute(MigrationPlan plan) {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Migration engine is closed");
            if (!running.compareAndSet(false, true)) throw new IllegalStateException("A migration is already running");
            if (!EXECUTION.tryAcquire()) {
                running.set(false);
                throw new IllegalStateException("A migration is already running in this JVM");
            }
            caller = Thread.currentThread();
        }
        Instant start = Instant.now();
        try {
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
                    completion.submit(() -> table(plan, task)); active++;
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
                        completion.submit(() -> table(plan, task)); active++;
                    }
                }
            }
            if (closed) throw new IllegalStateException("Migration stopped before all tasks completed");
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < plan.tables().size(); i++) order.put(plan.tables().get(i).name(), i);
            results.sort(Comparator.comparingInt(result -> order.get(result.taskName())));
            MigrationResult result = new MigrationResult(UUID.randomUUID(), start, Instant.now(), results);
            if (results.stream().anyMatch(t -> t.status() != TableResult.Status.SUCCEEDED)) throw new MigrationException(result);
            return result;
        } finally {
            synchronized (lifecycle) {
                running.set(false); caller = null; EXECUTION.release(); lifecycle.notifyAll();
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
        Instant start = Instant.now();
        CheckpointStore store = new CheckpointStore(plan.strategy().auditTableName(), plan.jobId(), task.name());
        boolean finalizedData = false;
        boolean checkpointValidated = false;
        try (Connection control = connections.openTargetConnection();
             Connection source = connections.openSourceConnection()) {
            DatabaseDialect dialect = DatabaseDialect.target(control);
            store.check(control);
            String query = SqlSupport.query(task);
            String canonicalTarget = dialect.canonicalTable(control, task.targetTable());
            Checkpoint summary = store.find(control, 0, false);
            checkpointValidated = true;
            if (summary != null && summary.succeeded()) return result(task, start, summary, TableResult.Status.SUCCEEDED, null);
            List<String> keys = preflight(source, control, query, task);
            if (summary == null) {
                store.create(control, 0, canonicalTarget, null, null, 0);
                control.commit();
            }
            summary = store.find(control, 0, false);
            finalizedData = summary.dataComplete();
            if (!finalizedData) {
                long total = count(source, query);
                // Keep preparation and optional DELETE in the summary transaction.
                summary = store.find(control, 0, true);
                if (summary.prepared() && total != summary.total()) throw new IllegalArgumentException("Source row count changed");
                if (task.keyed()) {
                    if (!summary.prepared()) {
                        if (task.mode() == TableTask.Mode.CLEAR_AND_REIMPORT) clear(control, task);
                        store.prepared(control, total);
                    }
                    control.commit();
                    keyed(plan, task, query, keys, source, control, store, canonicalTarget);
                    store.find(control, 0, true);
                    store.reconcile(control);
                    Checkpoint done = store.find(control, 0, false);
                    if (done.committed() + done.skipped() != total)
                        throw new SQLException("Planned count differs from source count", "P0004");
                    store.dataComplete(control);
                    control.commit();
                } else {
                    source.close(); // Reuse the source pool slot for the single keyless worker.
                    if (store.find(control, 1, false) == null) {
                        store.create(control, 1, canonicalTarget, null, null, total);
                        store.prepared(control, total);
                    }
                    control.commit();
                    transfer(plan, task, query, keys, store.find(control, 1, false), store, control);
                    store.find(control, 0, true);
                    store.dataComplete(control);
                    control.commit();
                }
                finalizedData = true;
            }
            store.find(control, 0, true);
            dialect.calibrate(control, task.targetTable(), task.sequence());
            // H2 ALTER SEQUENCE and PostgreSQL setval are not rollbackable, but neither
            // commits this transaction. Retain the summary lock through the final status.
            store.state(control, 0, "SUCCEEDED");
            control.commit();
            return result(task, start, store.find(control, 0, false), TableResult.Status.SUCCEEDED, null);
        } catch (Exception exception) {
            String state = exception instanceof CommitUnknownException ? "COMMIT_UNKNOWN" :
                    finalizedData ? "FINALIZATION_FAILED" : "FAILED";
            String error = exception instanceof IllegalArgumentException ? "INVALID_CONFIGURATION_OR_MAPPING" : SqlErrors.summary(exception);
            Checkpoint summary = null;
            try (Connection report = connections.openTargetConnection()) {
                summary = store.find(report, 0, false);
                if (checkpointValidated && summary != null) {
                    summary = store.find(report, 0, true);
                    if (!summary.succeeded()) { store.failure(report, 0, state, error); report.commit(); }
                }
            } catch (Exception ignored) { /* Database outage: return failure without claiming durable error persistence. */ }
            return result(task, start, summary, TableResult.Status.valueOf(state), error);
        }
    }

    private TableResult result(TableTask task, Instant start, Checkpoint checkpoint, TableResult.Status status, String error) {
        return new TableResult(task.name(), start, Instant.now(), checkpoint == null ? 0 : checkpoint.committed(),
                checkpoint == null ? 0 : checkpoint.skipped(), checkpoint == null ? 0 : checkpoint.retries(), status, error);
    }

    private List<String> preflight(Connection source, Connection target, String query, TableTask task) throws SQLException {
        List<String> keys;
        try (var statement = source.prepareStatement("SELECT * FROM (" + query + ") migration_source WHERE 1=0");
             var result = statement.executeQuery()) {
            JdbcRows.columns(result.getMetaData(), task.fields());
            keys = SqlSupport.resolveKeys(result.getMetaData(), task.primaryKey());
        }
        try (var insert = target.prepareStatement(task.insertSql())) {
            if (insert.getParameterMetaData().getParameterCount() != task.fields().size())
                throw new IllegalArgumentException("INSERT parameter metadata differs from fields");
        }
        if (task.keyed()) {
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
                    if (store.find(control, 0, false).planningComplete()) break;
                    planWindow(task, query, keys, source, control, store, window, target);
                    pending = store.pending(control, window);
                    if (pending.isEmpty()) continue;
                }
                List<Future<Void>> futures = new ArrayList<>();
                AtomicBoolean failed = new AtomicBoolean();
                for (Checkpoint batch : pending) {
                    futures.add(workers.submit(() -> {
                        if (failed.get()) return null; // Leave queued work durably pending for recovery.
                        try { transfer(plan, task, query, keys, batch, store, null); }
                        catch (Exception exception) { failed.set(true); throw exception; }
                        return null;
                    }));
                }
                Exception failure = null;
                for (Future<Void> future : futures) {
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
        control.commit();
    }

    private void transfer(MigrationPlan plan, TableTask task, String query, List<String> keys, Checkpoint batch,
                          CheckpointStore store, Connection progressConnection) throws Exception {
        MigrationStrategy strategy = plan.strategy();
        int retries = 0;
        boolean isolate = false;
        while (true) {
            checkRunning();
            boolean committedCall = false;
            boolean rolledBack = true; // A failed acquisition has not started any transaction.
            try (Connection target = connections.openTargetConnection()) {
                rolledBack = false;
                try {
                    Checkpoint current = store.find(target, batch.batch(), true);
                    if (current.succeeded()) { target.rollback(); return; }
                    store.state(target, batch.batch(), "RUNNING");
                    if (!task.keyed() && task.mode() == TableTask.Mode.CLEAR_AND_REIMPORT) clear(target, task);
                    JdbcRows.Counts counts;
                    try (Connection source = connections.openSourceConnection();
                         var read = SqlSupport.range(query, keys, batch.lower(), batch.upper(), false).prepare(source, task.batchSize());
                         var result = read.executeQuery();
                         var insert = target.prepareStatement(task.insertSql())) {
                        insert.setQueryTimeout(60);
                        counts = rows.copy(result, insert, task, strategy, isolate, processed -> {
                            if (progressConnection != null) {
                                store.progress(progressConnection, processed); progressConnection.commit();
                            }
                        });
                    }
                    if (counts.read() != batch.expected()) throw new SQLException("Source batch changed", "P0004");
                    checkRunning();
                    store.complete(target, batch.batch(), counts.written(), counts.skipped(), task.keyed() ? strategy.skipLimit() : 0);
                    committedCall = true;
                    target.commit();
                    return;
                } catch (Exception exception) {
                    if (!committedCall) {
                        try { target.rollback(); rolledBack = true; } catch (SQLException ignored) { }
                    }
                    throw exception;
                }
            } catch (Exception exception) {
                if (committedCall) {
                    try (Connection verify = connections.openTargetConnection()) {
                        // Lock waits for the original transaction to finish, not just a stale MVCC read.
                        Checkpoint current = store.find(verify, batch.batch(), true);
                        boolean success = current.succeeded();
                        verify.rollback();
                        if (success) return;
                        rolledBack = true;
                    } catch (Exception unresolved) { throw new CommitUnknownException(); }
                }
                if (exception instanceof SQLException sql && rolledBack && task.keyed() && !isolate
                        && strategy.skipLimit() > 0 && SqlErrors.data(sql)) {
                    isolate = true;
                    continue;
                }
                if (exception instanceof SQLException sql && rolledBack && SqlErrors.transientFailure(sql)
                        && retries < strategy.transientRetryCount()) {
                    retries++;
                    try (Connection report = connections.openTargetConnection()) {
                        store.attempt(report, batch.batch());
                        store.attempt(report, 0);
                        report.commit();
                    }
                    long base = strategy.retryInitialBackoff().toMillis();
                    long cap = strategy.retryMaxBackoff().toMillis();
                    long wait = base;
                    for (int i = 1; i < retries && wait < cap; i++) wait = wait > cap / 2 ? cap : wait * 2;
                    Thread.sleep(Math.min(wait, cap));
                    continue;
                }
                try (Connection report = connections.openTargetConnection()) {
                    store.failure(report, batch.batch(), "FAILED", SqlErrors.summary(exception)); report.commit();
                } catch (SQLException ignored) { }
                throw exception;
            }
        }
    }

    private void clear(Connection connection, TableTask task) throws SQLException {
        try (var statement = CheckpointStore.statement(connection, "DELETE FROM " + SqlSupport.table(task.targetTable()))) {
            statement.executeUpdate();
        }
    }

    private void checkRunning() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) throw new SQLException("Migration stopped", "57014");
    }

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
