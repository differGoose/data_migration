package com.datamigration.execution;

import com.datamigration.api.TableResult;
import com.datamigration.model.MigrationStrategy;
import com.datamigration.model.TableTask;
import java.util.UUID;
@FunctionalInterface
public interface TableTaskExecutor {
    TableResult execute(UUID runId, TableTask task, MigrationStrategy strategy);
}
