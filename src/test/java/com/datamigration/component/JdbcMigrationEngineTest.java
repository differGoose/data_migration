package com.datamigration.component;
import com.datamigration.support.*;
import com.datamigration.execution.*;

import com.datamigration.api.*;
import com.datamigration.model.*;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JdbcMigrationEngineTest {
    @Test void failureStopsQueuedBatchesOfThatTableButOtherTablesStillRun() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine()) {
            db.seed("A",250); db.seed("B",10);
            db.target("ALTER TABLE A ADD CONSTRAINT bad_id CHECK(ID<>1)");
            var original=db.task("A");
            var task=new TableTask(original.name(),original.selectSql(),original.insertSql(),original.fields(),
                    original.targetTable(),original.primaryKey(),1,original.mode(),original.sequence(), 100);
            var plan=db.plan(task,db.task("B"));
            assertThatThrownBy(() -> engine.execute(plan)).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
            assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(10);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='job' AND task_name='A' AND batch_no=2 AND state='PLANNED'")).isEqualTo(1);
            db.target("ALTER TABLE A DROP CONSTRAINT bad_id");
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(250);
        }
    }
    @Test void isolatedWriterSkipsOnlyConstraintFailures() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 220);
            db.target("ALTER TABLE A ADD CONSTRAINT bad_id CHECK (ID<>5 AND ID<>105)");
            try (var source = db.openSourceConnection(); var target = db.openTargetConnection();
                 var select = source.createStatement(); var result = select.executeQuery("SELECT * FROM A ORDER BY ID");
                 var insert = target.prepareStatement("INSERT INTO A VALUES (?,?)")) {
                var counts = new com.datamigration.jdbc.JdbcRows(new com.datamigration.conversion.JdbcValueConverter())
                    .copy(result, insert, db.task("A"), db.plan().strategy(), true, processed -> {});
                assertThat(counts.skipped()).isEqualTo(2);
                assertThat(counts.written()).isEqualTo(218);
                target.rollback();
            }
        }
    }
    @Test void copiesBatchesAndResumesCompletedTaskWithoutDuplicates() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("CUSTOMER", 235);
            var plan = db.plan(db.task("CUSTOMER"));
            assertThat(engine.execute(plan).tables().get(0).committedRows()).isEqualTo(235);
            assertThat(engine.execute(plan).tables().get(0).committedRows()).isEqualTo(235);
            assertThat(db.scalar("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo(235);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0 AND state='SUCCEEDED'")).isEqualTo(3);
        }
    }

    @Test void failedTableDoesNotStopAnotherTableAndCanResume() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 210); db.seed("B", 37);
            db.target("ALTER TABLE A ADD CONSTRAINT bad_id CHECK (ID<>101)");
            var plan = db.plan(db.task("A"), db.task("B"));
            MigrationException failure = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
            assertThat(failure.result().tables()).extracting(TableResult::status)
                    .containsExactly(TableResult.Status.FAILED, TableResult.Status.SUCCEEDED);
            assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(37);
            db.target("ALTER TABLE A DROP CONSTRAINT bad_id");
            assertThat(engine.execute(plan).tables()).allMatch(t -> t.status() == TableResult.Status.SUCCEEDED);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(210);
        }
    }

    @Test void keylessReimportIsAtomicAndRetainsOldRowsOnFailure() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 210);
            db.target("INSERT INTO A VALUES (999,'old')");
            db.target("ALTER TABLE A ADD CONSTRAINT bad_id CHECK (ID<>210)");
            var normal = db.task("A");
            var task = new TableTask(normal.name(), normal.selectSql(), normal.insertSql(), normal.fields(),
                    "A", List.of(), 1, TableTask.Mode.CLEAR_AND_REIMPORT, normal.sequence(), 100);
            var plan = db.plan(task);
            assertThatThrownBy(() -> engine.execute(plan)).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(1);
            assertThat(db.scalar("SELECT ID FROM A")).isEqualTo(999);
            db.target("ALTER TABLE A DROP CONSTRAINT bad_id");
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(210);
        }
    }

    @Test void compositeKeysArePersistedAndLobContentsSurvive() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.source("CREATE TABLE A(K1 BIGINT,K2 VARCHAR(20),DATA BLOB, PRIMARY KEY(K1,K2))");
            db.target("CREATE TABLE A(K1 BIGINT,K2 VARCHAR(20),DATA BLOB, PRIMARY KEY(K1,K2))");
            byte[] data = new byte[2 * 1024 * 1024]; new Random(7).nextBytes(data);
            try (var connection = db.openSourceConnection(); var statement = connection.prepareStatement("INSERT INTO A VALUES (?,?,?)")) {
                for (int i = 0; i < 5; i++) {
                    statement.setLong(1, i/2); statement.setString(2, "key-"+i); statement.setBytes(3, data); statement.executeUpdate();
                }
                connection.commit();
            }
            var task = new TableTask("A", "SELECT * FROM A ORDER BY K1,K2", "INSERT INTO A VALUES (?,?,?)",
                    List.of(new FieldMapping("K1", JDBCType.BIGINT), new FieldMapping("K2", JDBCType.VARCHAR),
                            new FieldMapping("DATA", JDBCType.VARBINARY)), "A", List.of("K1","K2"), 2,
                    TableTask.Mode.APPEND_RESUME, new SequenceSpec(false,null,null), 100);
            engine.execute(db.plan(task));
            try (var connection = db.openTargetConnection(); var result = connection.createStatement().executeQuery("SELECT DATA FROM A")) {
                int count = 0;
                while (result.next()) { assertThat(result.getBytes(1)).isEqualTo(data); count++; }
                assertThat(count).isEqualTo(5);
            }
        }
    }

    @Test void skipBudgetIsPerTableAcrossConcurrentBatches() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 220);
            db.target("ALTER TABLE A ADD CONSTRAINT bad_id CHECK (ID<>5 AND ID<>105)");
            var plan = db.plan("job", 2, db.task("A"));
            TableResult result;
            try { result = engine.execute(plan).tables().get(0); }
            catch (MigrationException failure) { throw new AssertionError(failure.result()); }
            assertThat(result.committedRows()).isEqualTo(218);
            assertThat(result.skippedRows()).isEqualTo(2);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(218);
        }
    }

    @Test void sequenceFinalizationDoesNotMoveBackwardsAndReimportRequiresNewJob() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 15);
            db.target("CREATE SEQUENCE A_SEQ START WITH 1");
            var normal = db.task("A");
            var task = new TableTask(normal.name(), normal.selectSql(), normal.insertSql(), normal.fields(),
                    "A", normal.primaryKey(), 1, TableTask.Mode.CLEAR_AND_REIMPORT, new SequenceSpec(true,"A_SEQ","ID"), 100);
            var plan = db.plan(task);
            engine.execute(plan);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(16);
            engine.execute(plan);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(17);
            engine.execute(db.plan("new-job", 0, task));
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(15);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(18);
        }
    }

    @Test void fingerprintMismatchIsRejected() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 10);
            engine.execute(db.plan(db.task("A")));
            var original = db.task("A");
            var changed = new TableTask(original.name(), "SELECT ID,NAME FROM A WHERE ID>1", original.insertSql(),
                    original.fields(), original.targetTable(), original.primaryKey(), 1, original.mode(), original.sequence(), 100);
            assertThatThrownBy(() -> engine.execute(db.plan(changed))).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(10);
        }
    }
}
