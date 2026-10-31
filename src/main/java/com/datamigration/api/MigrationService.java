package com.datamigration.api;

public interface MigrationService {
    /** Starts and awaits a new attempt. An existing running/successful attempt is returned immediately. */
    MigrationExecution migrate();
    /** Starts a new attempt without waiting, or returns the existing running/successful attempt. */
    MigrationExecution migrateAsync();
}
