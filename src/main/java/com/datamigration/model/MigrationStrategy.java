package com.datamigration.model;
import java.time.Duration;
public record MigrationStrategy(int fetchSize, int batchSize, int commitSize,
        long skipLimit, int transientRetryCount, Duration retryInitialBackoff,
        Duration retryMaxBackoff, int maxConcurrentTables, boolean auditEnabled,
        String auditTableName, long maxBatchBytes, long maxLobSize) {}
