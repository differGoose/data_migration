package com.datamigration.api;

import java.time.Instant;
public record TableResult(String taskName, Instant startedAt, Instant finishedAt,
        long committedRows, long skippedRows, int retries, Status status, String sanitizedError) {
    public enum Status {
        SUCCEEDED, FAILED, FINALIZATION_FAILED, COMMIT_UNKNOWN, VERIFICATION_ERROR, VERIFICATION_MISMATCH,
        /** Legacy status from the removed automatic cleanup workflow; never emitted by new executions. */
        @Deprecated VERIFICATION_FAILED,
        /** Legacy status from the removed automatic cleanup workflow; never emitted by new executions. */
        @Deprecated CLEANUP_PENDING
    }
}
