package com.datamigration.execution;

import com.datamigration.model.MigrationPlan;
import java.util.Comparator;

/** Worst-case connection demand for simultaneously scheduled tables. No database access. */
public record ConnectionBudget(int source, int target) {
    public static ConnectionBudget forPlan(MigrationPlan plan) {
        int parallel = plan.strategy().maxConcurrentTables();
        long sources = plan.tables().stream().mapToLong(t -> t.keyed() ? (long)t.workerCount() + 1 : 1)
                .boxed().sorted(Comparator.reverseOrder()).limit(parallel).mapToLong(Long::longValue).sum();
        long targets = plan.tables().stream().mapToLong(t -> (long)t.workerCount() + 1)
                .boxed().sorted(Comparator.reverseOrder()).limit(parallel).mapToLong(Long::longValue).sum();
        return new ConnectionBudget(Math.toIntExact(sources), Math.toIntExact(targets));
    }
}
