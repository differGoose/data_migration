package com.datamigration.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record MigrationResult(UUID runId, Instant startedAt, Instant finishedAt, List<TableResult> tables) {
    public MigrationResult { tables = List.copyOf(tables); }
}
