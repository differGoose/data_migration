package com.datamigration.support;

import com.datamigration.api.*;
import java.util.concurrent.ExecutionException;

/** Test/CLI boundary only: await async completion without hiding the original failure type. */
public final class MigrationAwait {
    private MigrationAwait() { }
    public static MigrationResult await(MigrationService service) { return await(service.migrate()); }
    public static MigrationResult await(MigrationExecution execution) {
        try { return execution.completion().toCompletableFuture().get(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted waiting for migration", failure); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Migration failed", failure.getCause());
        }
    }
}
