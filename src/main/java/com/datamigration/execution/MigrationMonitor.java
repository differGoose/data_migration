package com.datamigration.execution;

import com.datamigration.api.*;
import com.datamigration.audit.Checkpoint;
import com.datamigration.model.MigrationPlan;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Thread-safe, bounded to one execution; no business rows, SQL or key boundaries are retained. */
public final class MigrationMonitor implements MigrationObserver {
    private final UUID id = UUID.randomUUID();
    private final Instant started = Instant.now();
    private final Map<String, TableProgress> tables = new LinkedHashMap<>();
    private final CompletableFuture<MigrationResult> completion = new CompletableFuture<>();
    private final MigrationExecution execution = new MigrationExecution(this::snapshot, completion.minimalCompletionStage());
    private String job;
    private MigrationProgress.Status status = MigrationProgress.Status.RUNNING;
    private Instant finished;
    private String error;
    public UUID id() { return id; }
    public MigrationExecution execution() { return execution; }
    public synchronized boolean reusable() { return status != MigrationProgress.Status.FAILED || !completion.isDone(); }
    public synchronized MigrationProgress snapshot() {
        return new MigrationProgress(id, job, status, started, finished, new ArrayList<>(tables.values()), error);
    }
    public synchronized void plan(MigrationPlan plan) {
        job = plan.jobId();
        for (var task : plan.tables()) tables.put(task.name(), new TableProgress(task.name(), "PENDING", null, 0, 0, null));
    }
    private void state(String task, String state) {
        var previous = tables.get(task);
        tables.put(task, new TableProgress(task, state, previous.totalRows(), previous.committedRows(), previous.skippedRows(), null));
    }
    @Override public synchronized void tableStarted(String task) { state(task, "PREFLIGHT"); }
    @Override public synchronized void restored(String task, Checkpoint summary) {
        if (summary != null) tables.put(task, new TableProgress(task,
                summary.succeeded() ? "SUCCEEDED" : summary.dataComplete() ? "FINALIZING" : "PREFLIGHT",
                summary.prepared() ? summary.total() : null, summary.committed(), summary.skipped(), null));
    }
    @Override public synchronized void counted(String task, long total) {
        var previous = tables.get(task);
        tables.put(task, new TableProgress(task, "RUNNING", total, previous.committedRows(), previous.skippedRows(), null));
    }
    @Override public synchronized void committed(String task, long batch, long written, long skipped) {
        var previous = tables.get(task);
        tables.put(task, new TableProgress(task, "RUNNING", previous.totalRows(),
                previous.committedRows() + written, previous.skippedRows() + skipped, null));
    }
    @Override public synchronized void finalizing(String task) { state(task, "FINALIZING"); }
    @Override public synchronized void finished(TableResult result) {
        var previous = tables.get(result.taskName());
        tables.put(result.taskName(), new TableProgress(result.taskName(), result.status().name(), previous == null ? null : previous.totalRows(),
                result.committedRows(), result.skippedRows(), result.sanitizedError()));
    }
    public void succeed(MigrationResult result) {
        synchronized (this) { result.tables().forEach(this::finished); status = MigrationProgress.Status.SUCCEEDED; finished = Instant.now(); }
        completion.complete(result);
    }
    public void fail(Throwable failure) {
        synchronized (this) {
            status = MigrationProgress.Status.FAILED; finished = Instant.now(); error = SqlErrors.summary(failure);
            if (failure instanceof MigrationException migration) migration.result().tables().forEach(this::finished);
        }
        completion.completeExceptionally(failure);
    }
}
