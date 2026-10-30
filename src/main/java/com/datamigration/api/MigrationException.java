package com.datamigration.api;
/** All table tasks have finished; inspect result for partial commits and failures. */
public final class MigrationException extends RuntimeException {
    private final MigrationResult result;
    public MigrationException(MigrationResult result) {
        super("Migration finished with failed tables: " + result.tables().stream()
                .filter(table -> table.status() != TableResult.Status.SUCCEEDED)
                .map(table -> com.datamigration.diagnostics.MigrationLog.safe(table.taskName()) + " [" + table.status()
                        + "; " + safeError(table.sanitizedError()) + "]")
                .collect(java.util.stream.Collectors.joining(", "))
                + ". Inspect result for committed progress.");
        this.result = result;
    }
    private static String safeError(String error) {
        // Only known engine codes may enter a public exception message, never arbitrary supplied text.
        if (error != null && (error.matches("SQLSTATE=[A-Za-z0-9]{5}; vendor=-?[0-9]+")
                || error.matches("SQLSTATE=unknown; vendor=-?[0-9]+")
                || java.util.Set.of("INVALID_CONFIGURATION_OR_MAPPING", "MIGRATION_FAILED").contains(error))) return error;
        return "MIGRATION_FAILED";
    }
    public MigrationResult result() { return result; }
}
