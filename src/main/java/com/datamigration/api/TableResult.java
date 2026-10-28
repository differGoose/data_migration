package com.datamigration.api;

import java.time.Instant;
public record TableResult(String taskName, Instant startedAt, Instant finishedAt,
        long committedRows, long skippedRows, int retries, Status status, String sanitizedError) {
    public enum Status { SUCCEEDED, FAILED, FINALIZATION_FAILED, COMMIT_UNKNOWN }
}
