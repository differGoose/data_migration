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
    @Test void contentMismatchClearsOnlyFailedTableAndRestartsAllBatches() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 45); db.seed("B", 10);
            var plan = db.plan(verified(db.task("A"), "100", true), db.task("B"));
            var failed = catchThrowableOfType(() -> engine.execute(plan, mutate(db, "UPDATE A SET NAME='changed'")), MigrationException.class);
            assertThat(failed.result().tables().get(0).status()).isEqualTo(TableResult.Status.VERIFICATION_FAILED);
            assertThat(failed.result().tables().get(0).sanitizedError()).isEqualTo("VERIFICATION_CONTENT_MISMATCH");
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
            assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(10);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND batch_no>0")).isZero();
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND state='VERIFICATION_FAILED' AND prepared=FALSE AND data_complete=FALSE AND committed_rows=0")).isEqualTo(1);
            // New engine simulates loss of all in-memory state.
            try (var restarted = db.engine()) { restarted.execute(plan); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(45);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE task_name='A' AND batch_no>0")).isEqualTo(3);
        }
    }
    @Test void countMismatchAlsoDeletesPreexistingTargetRows() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 2); db.target("INSERT INTO A VALUES(999,'old')");
            var plan = db.plan(verified(db.task("A"), "100", true));
            var failed = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
            assertThat(failed.result().tables().get(0).sanitizedError()).isEqualTo("VERIFICATION_COUNT_MISMATCH");
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
            engine.execute(plan);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(2);
        }
    }
    @Test void failedDeleteAndAuditResetRollBackTogetherAndRetryCleanupFirst() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 25);
            db.target("ALTER TABLE migration_audit ADD CONSTRAINT reject_reset CHECK(state<>'VERIFICATION_FAILED')");
            var plan = db.plan(verified(db.task("A"), "100", false));
            var failed = catchThrowableOfType(() -> engine.execute(plan, mutate(db, "UPDATE A SET NAME='bad'")), MigrationException.class);
            assertThat(failed.result().tables().get(0).status()).isEqualTo(TableResult.Status.CLEANUP_PENDING);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no=1")).isEqualTo(1);
            db.target("ALTER TABLE migration_audit DROP CONSTRAINT reject_reset");
            var progress = new MigrationMonitor(); progress.plan(plan);
            var observed = new MigrationObserver() {
                @Override public void restored(String task, com.datamigration.audit.Checkpoint summary) { progress.restored(task, summary); }
                @Override public void counted(String task, long total) { progress.counted(task,total); }
                @Override public void committed(String task, long batch, long written, long skipped) {
                    progress.committed(task,batch,written,skipped);
                    assertThat(progress.snapshot().committedRows()).isEqualTo(25);
                }
            };
            try (var restarted = db.engine()) { restarted.execute(plan,observed); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='bad'")).isZero();
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
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
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cleanupCommitResponseLossUsesDurableSummaryToChooseRecovery(boolean committed) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 25);
            AtomicBoolean fired = new AtomicBoolean();
            ConnectionAccess access = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    Connection real = db.openTargetConnection(); AtomicBoolean cleanup = new AtomicBoolean();
                    return proxy(Connection.class, real, (method,args) -> {
                        if (method.getName().equals("prepareStatement") && args[0].equals("DELETE FROM A")) cleanup.set(true);
                        if (method.getName().equals("commit") && cleanup.get() && fired.compareAndSet(false,true)) {
                            if (committed) real.commit(); else real.rollback();
                            throw new SQLTransientConnectionException("secret response lost", "08006");
                        }
                        return invoke(real,method,args);
                    });
                }
            };
            var plan = db.plan(verified(db.task("A"),"100",true));
            try (var engine = db.engine(access)) {
                var failure = catchThrowableOfType(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='bad'")),MigrationException.class);
                assertThat(failure.result().tables().get(0).status()).isEqualTo(committed ? TableResult.Status.VERIFICATION_FAILED : TableResult.Status.CLEANUP_PENDING);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(committed ? 0 : 25);
            }
            try (var restarted = db.engine()) { restarted.execute(plan); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(25);
            assertThat(db.scalar("SELECT COUNT(*) FROM A WHERE NAME='bad'")).isZero();
        }
    }
    @Test void cleanupIntentMustBeDurableBeforeDeletingRows() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 3);
            db.target("ALTER TABLE migration_audit ADD CONSTRAINT no_intent CHECK(state<>'CLEANUP_PENDING')");
            var plan = db.plan(verified(db.task("A"),"100",true));
            assertThatThrownBy(() -> engine.execute(plan,mutate(db,"UPDATE A SET NAME='bad'"))).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(3);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0 AND state='SUCCEEDED'")).isEqualTo(1);
            db.target("ALTER TABLE migration_audit DROP CONSTRAINT no_intent");
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
