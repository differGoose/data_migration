package com.datamigration.component;
import com.datamigration.support.*;
import com.datamigration.execution.*;

import com.datamigration.api.*;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionIsolationTest {
    @Test void closeCancelsInFlightWorkAndWaitsForRollbackBeforeReturning() throws Exception {
        var callers=Executors.newSingleThreadExecutor();
        try (var db=new DatabaseFixture(); var callerScope=new ExecutorScope(callers)) {
            db.seed("A",250); db.seed("B",10); db.seed("C",10);
            CountDownLatch entered=new CountDownLatch(1), workerStopped=new CountDownLatch(1);
            var converter=new com.datamigration.conversion.JdbcValueConverter();
            var engine=new JdbcMigrationEngine(db,20,20,(value,type) -> {
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
                // Resource cleanup must release the JVM permit for a fresh engine.
                try (var next=db.engine()) {
                    assertThat(next.execute(db.plan(db.task("A"),db.task("B"),db.task("C"))).tables()).hasSize(3);
                }
            } finally { engine.close(); }
        }
    }

    @Test void sameServiceRejectsOverlappingCallsAndClosedEngineCannotRestart() throws Exception {
        var callers=Executors.newSingleThreadExecutor();
        try (var db=new DatabaseFixture(); var callerScope=new ExecutorScope(callers)) {
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
                try (var other=db.engine()) {
                    assertThatThrownBy(() -> other.execute(db.plan(db.task("B")))).hasMessageContaining("already running");
                }
                // Closing an idle engine must not release the active engine's permit.
                try (var third=db.engine()) {
                    assertThatThrownBy(() -> third.execute(db.plan(db.task("C")))).hasMessageContaining("already running");
                }
                release.countDown();
                assertThat(first.get(15,TimeUnit.SECONDS).tables().get(0).committedRows()).isEqualTo(10);
            } finally { release.countDown(); engine.close(); }
            assertThatThrownBy(() -> engine.execute(db.plan(db.task("A")))).hasMessageContaining("closed");
        }
    }

    @Test void noTasksInvalidJobAndInsufficientCapacityFailBeforeWriting() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine()) {
            db.seed("A",10);
            assertThatThrownBy(() -> engine.execute(db.plan())).hasMessageContaining("No enabled");
            assertThatThrownBy(() -> engine.execute(db.plan("__reserved",0,db.task("A")))).hasMessageContaining("Invalid job");
            try (var small=new JdbcMigrationEngine(db,1,1,new com.datamigration.conversion.JdbcValueConverter())) {
                assertThatThrownBy(() -> small.execute(db.plan(db.task("A")))).hasMessageContaining("Insufficient pool");
            }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
        }
    }
}
