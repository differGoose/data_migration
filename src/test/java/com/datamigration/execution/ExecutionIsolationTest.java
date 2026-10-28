package com.datamigration.execution;

import com.datamigration.api.*;
import com.datamigration.audit.TableLock;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionIsolationTest {
    @Test void closeCancelsInFlightWorkAndWaitsForRollbackBeforeReturning() throws Exception {
        try (var db=new DatabaseFixture(); var callers=Executors.newSingleThreadExecutor()) {
            db.seed("A",250); db.seed("B",10); db.seed("C",10);
            CountDownLatch entered=new CountDownLatch(1), workerStopped=new CountDownLatch(1);
            var converter=new com.datamigration.conversion.JdbcValueConverter();
            var engine=new JdbcMigrationEngine(db,20,20,db.sourceUrl+"|"+db.targetUrl,(value,type) -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException exception) {
                    workerStopped.countDown(); Thread.currentThread().interrupt();
                    throw new SQLException("cancelled", "57014");
                }
                return converter.convert(value,type);
            });
            var first=callers.submit(() -> engine.execute(db.plan(db.task("A"),db.task("B"),db.task("C"))));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),engine::close);
                assertThat(workerStopped.getCount()).isZero();
                assertThatThrownBy(() -> first.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
                assertThat(db.scalar("SELECT COUNT(*) FROM B")).isZero();
                assertThat(db.scalar("SELECT COUNT(*) FROM C")).isZero();
                // Resource cleanup must leave the table locks available for a fresh engine.
                try (var next=db.engine()) {
                    assertThat(next.execute(db.plan(db.task("A"),db.task("B"),db.task("C"))).tables()).hasSize(3);
                }
            } finally { engine.close(); }
        }
    }

    @Test void anotherEngineCannotWriteWhileTargetLockIsHeld() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine(); var control=db.openTargetConnection()) {
            db.seed("A",10);
            try (var guard=TableLock.acquire(db,control,"migration_audit","PUBLIC.A")) {
                guard.check();
                assertThatThrownBy(() -> engine.execute(db.plan(db.task("A")))).isInstanceOf(MigrationException.class);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
            }
            assertThat(engine.execute(db.plan(db.task("A"))).tables().getFirst().committedRows()).isEqualTo(10);
        }
    }

    @Test void configurationMismatchMustNotOverwriteSuccessfulAudit() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine()) {
            db.seed("A",10);
            engine.execute(db.plan(db.task("A")));
            var old=db.task("A");
            var changed=new com.datamigration.model.TableTask(old.name(),"SELECT ID,NAME FROM A WHERE ID>5",
                    old.insertSql(),old.fields(),old.targetTable(),old.primaryKey(),old.workerCount(),old.mode(),old.sequence());
            assertThatThrownBy(() -> engine.execute(db.plan(changed))).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='job' AND batch_no=0 AND state='SUCCEEDED'")).isEqualTo(1);
        }
    }

    @Test void sameServiceRejectsOverlappingCallsAndClosedEngineCannotRestart() throws Exception {
        try (var db=new DatabaseFixture(); var callers=Executors.newSingleThreadExecutor()) {
            db.seed("A",10);
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
            ConnectionAccess delayed=new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    entered.countDown();
                    try {
                        if (!release.await(10,TimeUnit.SECONDS)) throw new SQLException("Test timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                    return db.openTargetConnection();
                }
            };
            var engine=db.engine(delayed);
            try {
                var first=callers.submit(() -> engine.execute(db.plan(db.task("A"))));
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> engine.execute(db.plan(db.task("A")))).hasMessageContaining("already running");
                release.countDown();
                assertThat(first.get(15,TimeUnit.SECONDS).tables().getFirst().committedRows()).isEqualTo(10);
            } finally { release.countDown(); engine.close(); }
            assertThatThrownBy(() -> engine.execute(db.plan(db.task("A")))).hasMessageContaining("closed");
        }
    }

    @Test void noTasksInvalidJobAndInsufficientCapacityFailBeforeWriting() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine()) {
            db.seed("A",10);
            assertThatThrownBy(() -> engine.execute(db.plan())).hasMessageContaining("No enabled");
            assertThatThrownBy(() -> engine.execute(db.plan("__reserved",0,db.task("A")))).hasMessageContaining("Invalid job");
            try (var small=new JdbcMigrationEngine(db,1,1,"test",new com.datamigration.conversion.JdbcValueConverter())) {
                assertThatThrownBy(() -> small.execute(db.plan(db.task("A")))).hasMessageContaining("Insufficient pool");
            }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
        }
    }
}
