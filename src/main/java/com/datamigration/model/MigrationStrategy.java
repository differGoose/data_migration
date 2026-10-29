package com.datamigration.model;
import java.time.Duration;
public record MigrationStrategy(long skipLimit, int transientRetryCount, Duration retryInitialBackoff,
        Duration retryMaxBackoff, int maxConcurrentTables, boolean auditEnabled,
        String auditTableName, long maxBatchBytes, long maxLobSize) {}
