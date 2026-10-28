package com.datamigration.audit;

import com.datamigration.support.DatabaseFixture;
import com.datamigration.execution.ExecutorScope;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TableLockTest {
    @Test void takeoverFencesStaleWorkersAndWaitsForTheirTransactions() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var db = new DatabaseFixture(); var control = db.openTargetConnection();
             var oldWorker = db.openTargetConnection(); var newControl = db.openTargetConnection();
             var executorScope = new ExecutorScope(executor)) {
            var old = TableLock.acquire(db,control,"migration_audit","PUBLIC.A");
            var batches = new CheckpointStore("migration_audit","job","A");
            batches.create(control,1,"PUBLIC.A","fingerprint",null,null,10);
            control.commit();
            batches.find(oldWorker,1,true);
            old.assertOwner(oldWorker);
            old.close(); // Simulates losing the guard while an old business transaction is still alive.
            var future = executor.submit(() -> TableLock.acquire(db,newControl,"migration_audit","PUBLIC.A"));
            try {
                // Wait for the committed ownership fence, not a scheduling sleep.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean fenced = false;
                while (System.nanoTime() < deadline) {
                    try { old.assertOwner(oldWorker); }
                    catch (SQLException stale) { fenced = true; break; }
                    Thread.sleep(10);
                }
                assertThat(fenced).isTrue();
                assertThat(future.isDone()).isFalse(); // New owner cannot clear while old batch lock exists.
                oldWorker.rollback();
                try (var replacement = future.get(5,TimeUnit.SECONDS)) {
                    replacement.assertOwner(oldWorker);
                    assertThatThrownBy(() -> old.assertOwner(oldWorker)).isInstanceOf(SQLException.class)
                            .hasMessageContaining("Stale");
                }
            } finally { oldWorker.rollback(); }
        }
    }
}
