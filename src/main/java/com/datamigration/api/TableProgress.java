package com.datamigration.api;

/** totalRows is null until the source count or a durable summary has been read. */
public record TableProgress(String taskName, String state, Long totalRows,
        long committedRows, long skippedRows, String sanitizedError) { }
