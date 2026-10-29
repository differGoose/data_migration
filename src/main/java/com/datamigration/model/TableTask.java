package com.datamigration.model;
import java.util.List;
public record TableTask(String name, String selectSql, String insertSql, List<FieldMapping> fields,
        String targetTable, List<String> primaryKey, int workerCount, Mode mode, SequenceSpec sequence, int batchSize) {
    public TableTask {
        if (batchSize < 1) throw new IllegalArgumentException("batch-size must be positive"); fields = List.copyOf(fields); primaryKey = List.copyOf(primaryKey); }
    public enum Mode { APPEND_RESUME, CLEAR_AND_REIMPORT }
    public boolean keyed() { return !primaryKey.isEmpty(); }
    @Override public String toString() { return "TableTask[SQL redacted]"; }
}
