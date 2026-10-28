package com.datamigration.execution;

import com.datamigration.api.MigrationResult;
import com.datamigration.model.MigrationPlan;
/** Implementations own scheduling and failure propagation, not connection-pool lifecycle. */
@FunctionalInterface
public interface MigrationEngine {
    MigrationResult execute(MigrationPlan plan);
}
