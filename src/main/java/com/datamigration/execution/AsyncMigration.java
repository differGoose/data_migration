package com.datamigration.execution;

import com.datamigration.api.MigrationExecution;
import com.datamigration.api.MigrationResult;
import com.datamigration.diagnostics.MigrationLog;
import java.util.function.Function;

/** Owns one background caller thread per attempt; the engine still owns its bounded workers. */
public final class AsyncMigration implements AutoCloseable {
    private final Function<MigrationMonitor, MigrationResult> work;
    private MigrationMonitor current;
    private Thread worker;
    private boolean closed;
    public AsyncMigration(Function<MigrationMonitor, MigrationResult> work) { this.work = work; }
    public synchronized MigrationExecution start() {
        if (closed) throw new IllegalStateException("Migration service is closed");
        if (current != null && current.reusable()) {
            try (var diagnostic = MigrationLog.invocation(current.id())) {
                MigrationLog.job(current.snapshot().jobId());
                MigrationLog.stage("MIGRATION_REUSE", "-", "data-migration");
                MigrationLog.info(current.snapshot().status() == com.datamigration.api.MigrationProgress.Status.SUCCEEDED
                        ? "Migration already succeeded; returning existing result"
                        : "Migration already running; returning current progress", "");
            }
            return current.execution();
        }
        var monitor = new MigrationMonitor();
        current = monitor;
        worker = new Thread(() -> run(monitor), "migration-" + monitor.id());
        worker.setDaemon(false);
        try { worker.start(); }
        catch (RuntimeException | Error failure) { worker = null; monitor.fail(failure); }
        return monitor.execution();
    }
    public MigrationExecution startSync() {
        MigrationExecution execution;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Migration service is closed");
            if (current != null && current.reusable()) return start();
            execution = start();
        }
        try { execution.completion().toCompletableFuture().get(); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Migration failed", failure.getCause());
        } catch (InterruptedException failure) {
            Thread active;
            synchronized (this) { active = current.execution() == execution ? worker : null; }
            stop(active);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Migration interrupted", failure);
        }
        return execution;
    }
    private void run(MigrationMonitor monitor) {
        try (var diagnostic = MigrationLog.invocation(monitor.id())) {
            try { monitor.succeed(work.apply(monitor)); }
            catch (Throwable failure) {
                if (!(failure instanceof com.datamigration.api.MigrationException)) MigrationLog.error("Background migration failed", failure);
                monitor.fail(failure);
            }
        } finally {
            synchronized (this) { if (worker == Thread.currentThread()) worker = null; }
        }
    }
    @Override public void close() { close(true); }
    public void close(boolean wait) {
        Thread active;
        synchronized (this) { closed = true; active = worker; }
        if (wait) stop(active);
        else if (active != null) active.interrupt();
    }
    private void stop(Thread active) {
        if (active == null || active == Thread.currentThread()) return;
        active.interrupt();
        boolean interrupted = false;
        while (active.isAlive()) {
            try { active.join(); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
