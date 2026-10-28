package com.datamigration.model;
import java.util.List;
public record MigrationPlan(String jobId, MigrationStrategy strategy, List<TableTask> tables) {
    public MigrationPlan { tables = List.copyOf(tables); }
}
