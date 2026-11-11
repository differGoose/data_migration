package com.datamigration.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable in-memory snapshot. Counts include confirmed commits, never uncommitted writes. */
public record MigrationProgress(UUID runId, String jobId, Status status, Instant startedAt,
        Instant finishedAt, List<TableProgress> tables, String sanitizedError) {
    public enum Status { RUNNING, SUCCEEDED, FAILED }
    public MigrationProgress { tables = List.copyOf(tables); }
    public long committedRows() { return tables.stream().mapToLong(TableProgress::committedRows).sum(); }
    public long skippedRows() { return tables.stream().mapToLong(TableProgress::skippedRows).sum(); }
}
