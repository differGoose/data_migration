package com.datamigration.execution;

import com.datamigration.api.TableResult;
import com.datamigration.audit.Checkpoint;

/** Internal progress sink; notifications do not access the database or alter transactions. */
public interface MigrationObserver {
    MigrationObserver NONE = new MigrationObserver() { };
    default void tableStarted(String task) { }
    default void restored(String task, Checkpoint summary) { }
    default void counted(String task, long total) { }
    default void committed(String task, long batch, long written, long skipped) { }
    default void finalizing(String task) { }
    default void finished(TableResult result) { }
}
