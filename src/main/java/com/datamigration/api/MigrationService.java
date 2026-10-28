package com.datamigration.api;

/** Synchronous entry point; failures propagate to the caller. */
@FunctionalInterface
public interface MigrationService {
    MigrationResult migrate();
}
