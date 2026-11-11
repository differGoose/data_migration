package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.execution.*;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.model.*;
import com.datamigration.support.DatabaseFixture;
import java.math.BigDecimal;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TableVerificationTest {
    static TableTask verified(TableTask task, String percent, boolean keyed) {
        return new TableTask(task.name(), task.selectSql(), task.insertSql(), task.fields(), task.targetTable(),
                keyed ? task.primaryKey() : List.of(), keyed ? task.workerCount() : 1, task.mode(), task.sequence(),
                20, new VerificationSpec(true, new BigDecimal(percent)));
    }
    private MigrationObserver mutate(DatabaseFixture db, String sql) {
        return new MigrationObserver() {
            @Override public void phase(String task, String state) {
                if (state.equals("VERIFYING")) try { db.target(sql); } catch (SQLException e) { throw new IllegalStateException(e); }
            }
        };
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void differentPercentagesAndRepeatedExecutionPreserveAllRows(boolean keyed) throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 101); db.seed("B", 23);
            var plan = db.plan(verified(db.task("A"), "10", keyed), verified(db.task("B"), "100", keyed));
            assertThat(engine.execute(plan).tables()).allMatch(t -> t.status() == TableResult.Status.SUCCEEDED);
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(101);
            assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(23);
        }
    }
    @Test void emptyAndFractionalSamplingAreValid() throws Exception {
        assertThat(new VerificationSpec(true, new BigDecimal("10")).samples(101)).isEqualTo(11);
        assertThat(new VerificationSpec(true, new BigDecimal("0.001")).samples(100)).isEqualTo(1);
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 0); engine.execute(db.plan(verified(db.task("A"), "0.1", true)));
        }
        for (String percent : List.of("0", "-1", "100.1"))
            assertThatThrownBy(() -> new VerificationSpec(true, new BigDecimal(percent))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationSpec(true, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contentMismatchPreservesDataAndCheckpointsAndOnlyReverifies() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",45); db.seed("B",10);
            var plan = db.plan(verified(db.task("A"),"100",true),db.task("B"));
            var failed = catchThrowableOfType(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='changed'")),MigrationException.class);
            assertThat(failed.result().tables().get(0).status()).isEqualTo(TableResult.Status.VERIFICATION_MISMATCH);
            assertThat(failed.result().tables().get(0).sanitizedError()).isEqualTo("VERIFICATION_CONTENT_MISMATCH");
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='changed'")).isEqualTo(45);
            assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(10);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND batch_no>0 AND state='SUCCEEDED'")).isEqualTo(3);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND batch_no=0 AND state='VERIFICATION_MISMATCH' AND prepared=TRUE AND data_complete=TRUE AND committed_rows=45")).isEqualTo(1);
            assertThatThrownBy(() -> engine.execute(plan)).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='changed'")).isEqualTo(45);
            db.target("UPDATE A SET NAME='row-' || ID");
            try (var restarted = db.engine()) { restarted.execute(plan); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(45);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND batch_no>0")).isEqualTo(3);
        }
    }


    @Test void countMismatchPreservesPreexistingRowsUntilManualRepair() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",2); db.target("INSERT INTO A VALUES(999,'old')");
            var plan = db.plan(verified(db.task("A"),"100",true));
            for (int attempt=0; attempt<2; attempt++) {
                var failed = catchThrowableOfType(() -> engine.execute(plan),MigrationException.class);
                assertThat(failed.result().tables().get(0).sanitizedError()).isEqualTo("VERIFICATION_COUNT_MISMATCH");
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(3);
                assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE ID=999")).isEqualTo(1);
            }
            db.target("DELETE FROM A WHERE ID=999");
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(2);
        }
    }


    @ParameterizedTest @ValueSource(booleans = {true,false})
    void reimportModeDoesNotClearCommittedDataOnVerificationRetry(boolean keyed) throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",25);
            var base = verified(db.task("A"),"100",keyed);
            var task = new TableTask(base.name(),base.selectSql(),base.insertSql(),base.fields(),base.targetTable(),base.primaryKey(),
                    base.workerCount(),TableTask.Mode.CLEAR_AND_REIMPORT,base.sequence(),base.batchSize(),base.verification());
            var plan = db.plan(task);
            assertThatThrownBy(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='bad'"))).isInstanceOf(MigrationException.class);
            assertThatThrownBy(() -> engine.execute(plan)).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='bad'")).isEqualTo(25);
            db.target("UPDATE A SET NAME='row-' || ID");
            var progress = new MigrationMonitor(); progress.plan(plan);
            engine.execute(plan,progress);
            assertThat(progress.snapshot().committedRows()).isEqualTo(25);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0")).isEqualTo(keyed ? 2 : 1);
        }
    }


    @Test void queryTimeoutRetainsDataAndOnlyRetriesFinalization() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 25);
            AtomicBoolean fault = new AtomicBoolean(true);
            ConnectionAccess access = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    Connection real = db.openTargetConnection();
                    return proxy(Connection.class, real, (method, args) -> {
                        Object returned = invoke(real, method, args);
                        if (method.getName().equals("createStatement")) {
                            Statement statement = (Statement) returned;
                            return proxy(Statement.class, statement, (operation, parameters) -> {
                                if (operation.getName().equals("executeQuery") && parameters[0].equals("SELECT COUNT(*) FROM A") && fault.get())
                                    throw new SQLTimeoutException("secret business SQL", "57014");
                                return invoke(statement, operation, parameters);
                            });
                        }
                        return returned;
                    });
                }
            };
            var plan = db.plan(verified(db.task("A"), "100", true));
            try (var engine = db.engine(access)) {
                var failed = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
                assertThat(failed.result().tables().get(0).status()).isEqualTo(TableResult.Status.VERIFICATION_ERROR);
                assertThat(failed.getMessage()).doesNotContain("secret");
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
                long batches = db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0");
                fault.set(false); engine.execute(plan);
                assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0")).isEqualTo(batches);
            }
        }
    }
    @Test void sequenceOnlyRunsAfterVerificationAndIsRetriedAfterFailure() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 25);
            TableTask base = verified(db.task("A"), "100", true);
            var task = new TableTask(base.name(), base.selectSql(), base.insertSql(), base.fields(), "A", base.primaryKey(), 2,
                    base.mode(), new SequenceSpec(true, "A_SEQ", "ID"), 20, base.verification());
            var plan = db.plan(task);
            var failed = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
            assertThat(failed.result().tables().get(0).status()).isEqualTo(TableResult.Status.FINALIZATION_FAILED);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
            db.target("CREATE SEQUENCE A_SEQ START WITH 1");
            engine.execute(plan);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(26);
        }
    }
    @Test void invalidConfigurationFailsBeforeAnyDataIsWritten() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 2); var task = verified(db.task("A"), "100", true);
            assertThatThrownBy(() -> engine.execute(db.plan("skip", 1, task))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skip-limit");
            var expression = new TableTask(task.name(), task.selectSql(), "INSERT INTO A(ID,NAME) VALUES (?,UPPER(?))", task.fields(),
                    "A", task.primaryKey(), 2, task.mode(), task.sequence(), 20, task.verification());
            assertThatThrownBy(() -> engine.execute(db.plan(expression))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain ?");
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true,false})
    void mismatchReportCommitResponseLossNeverDeletesDataOrReplaysBatches(boolean committed) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A",25); AtomicBoolean fired = new AtomicBoolean();
            ConnectionAccess access = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    Connection real = db.openTargetConnection(); AtomicBoolean report = new AtomicBoolean();
                    return proxy(Connection.class,real,(method,args) -> {
                        if (method.getName().equals("prepareStatement") && args[0].toString().contains("state=?,error_code=?")) report.set(true);
                        if (method.getName().equals("commit") && report.get() && fired.compareAndSet(false,true)) {
                            if (committed) real.commit(); else real.rollback();
                            throw new SQLTransientConnectionException("secret response lost","08006");
                        }
                        return invoke(real,method,args);
                    });
                }
            };
            var plan = db.plan(verified(db.task("A"),"100",true));
            try (var engine = db.engine(access)) {
                var failure = catchThrowableOfType(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='bad'")),MigrationException.class);
                assertThat(failure.result().tables().get(0).status()).isEqualTo(TableResult.Status.VERIFICATION_MISMATCH);
                assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='bad'")).isEqualTo(25);
            }
            assertThat(fired).isTrue();
            db.target("UPDATE A SET NAME='row-' || ID");
            try (var restarted = db.engine()) { restarted.execute(plan); }
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0")).isEqualTo(2);
        }
    }


    @Test void failedMismatchAuditWriteRetainsDataAndOriginalReason() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",3);
            db.target("ALTER TABLE migration_audit ADD CONSTRAINT reject_mismatch CHECK(state<>'VERIFICATION_MISMATCH')");
            var plan = db.plan(verified(db.task("A"),"100",true));
            var failure = catchThrowableOfType(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='bad'")),MigrationException.class);
            assertThat(failure.result().tables().get(0).sanitizedError()).isEqualTo("VERIFICATION_CONTENT_MISMATCH");
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='bad'")).isEqualTo(3);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0 AND state='SUCCEEDED'")).isEqualTo(1);
            db.target("ALTER TABLE migration_audit DROP CONSTRAINT reject_mismatch");
            db.target("UPDATE A SET NAME='row-' || ID"); engine.execute(plan);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CLEANUP_PENDING","VERIFICATION_FAILED"})
    void legacyCleanupStatesRequireManualResetAndNeverModifyData(String legacy) throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",3); var plan = db.plan(verified(db.task("A"),"100",true)); engine.execute(plan);
            db.target("UPDATE migration_audit SET state='"+legacy+"', error_code='VERIFICATION_CONTENT_MISMATCH' WHERE batch_no=0");
            var failure = catchThrowableOfType(() -> engine.execute(plan),MigrationException.class);
            assertThat(failure.result().tables().get(0).sanitizedError()).isEqualTo("LEGACY_VERIFICATION_RESET_REQUIRED");
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(3);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no=0 AND state='"+legacy+"'")).isEqualTo(1);
            // Host explicitly resets its synthetic fixture, then the unchanged job migrates afresh.
            db.target("DELETE FROM A"); db.target("DELETE FROM migration_audit WHERE job_id='job' AND task_name='A'");
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(3);
        }
    }


    @ParameterizedTest @ValueSource(booleans = {true, false})
    void compositeKeysAndKeylessVerificationFinishAtMinimumConnectionBudget(boolean keyed) throws Exception {
        try (var db = new DatabaseFixture();
             var engine = new JdbcMigrationEngine(db,keyed ? 2 : 1,2,new com.datamigration.conversion.JdbcValueConverter())) {
            String ddl = "CREATE TABLE A(TENANT BIGINT,ID BIGINT,NAME VARCHAR,PRIMARY KEY(TENANT,ID))";
            db.source(ddl); db.target(ddl);
            db.source("INSERT INTO A VALUES(1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d'),(3,1,'e')");
            var task = new TableTask("A","SELECT TENANT,ID,NAME FROM A","INSERT INTO A(TENANT,ID,NAME) VALUES(?,?,?)",
                    List.of(new FieldMapping("TENANT",JDBCType.BIGINT),new FieldMapping("ID",JDBCType.BIGINT),new FieldMapping("NAME",JDBCType.VARCHAR)),
                    "A",keyed ? List.of("TENANT","ID") : List.of(),1,TableTask.Mode.APPEND_RESUME,
                    new SequenceSpec(false,null,null),2,new VerificationSpec(true,new BigDecimal("100")));
            engine.execute(db.plan(task));
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(5);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0 AND state='SUCCEEDED'")).isEqualTo(keyed ? 3 : 1);
        }
    }
    @Test void verificationLogsNeverContainBusinessValuesOrRowDigests() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.datamigration");
        var old = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A",3); db.source("UPDATE A SET NAME='TOP_SECRET_BUSINESS_VALUE'");
            assertThatThrownBy(() -> engine.execute(db.plan(verified(db.task("A"),"100",true)),mutate(db,"UPDATE A SET NAME='OTHER_SECRET_VALUE'")))
                    .isInstanceOf(MigrationException.class);
            String log = String.join("\n",appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            assertThat(log).contains("VERIFICATION_CONTENT_MISMATCH").doesNotContain("TOP_SECRET_BUSINESS_VALUE","OTHER_SECRET_VALUE","UPDATE A SET","INSERT INTO");
            assertThat(log).doesNotMatch("(?s).*[a-f0-9]{64}.*");
        } finally { logger.detachAppender(appender); appender.stop(); logger.setLevel(old); }
    }
    @Test void customSourceConverterIsAppliedExactlyOnceAndNeverToTargetRows() throws Exception {
        try (var db = new DatabaseFixture();
             var engine = new JdbcMigrationEngine(db,3,3,(value,type) -> {
                 if (value != null && type == JDBCType.VARCHAR) return value + "!";
                 return new com.datamigration.conversion.JdbcValueConverter().convert(value,type);
             })) {
            db.seed("A",3);
            engine.execute(db.plan(verified(db.task("A"),"100",true)));
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='row-' || ID || '!'")).isEqualTo(3);
        }
    }
    private interface Call { Object call(Method method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, T real, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> call.call(method,args));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
