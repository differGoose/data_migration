package com.datamigration.api;
/** All table tasks have finished; inspect result for partial commits and failures. */
public final class MigrationException extends RuntimeException {
    private final MigrationResult result;
    public MigrationException(MigrationResult result) {
        super("Migration finished with failed tables; inspect result for committed progress.");
        this.result = result;
    }
    public MigrationResult result() { return result; }
}
