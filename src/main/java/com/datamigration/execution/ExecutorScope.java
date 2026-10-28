package com.datamigration.execution;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Java 17 equivalent of waiting for an owned executor on leaving a scope. */
public final class ExecutorScope implements AutoCloseable {
    private final ExecutorService executor;

    public ExecutorScope(ExecutorService executor) {
        this.executor = executor;
    }

    @Override public void close() {
        executor.shutdown();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException exception) {
                if (!interrupted) executor.shutdownNow();
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
