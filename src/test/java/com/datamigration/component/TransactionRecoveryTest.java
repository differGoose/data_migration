package com.datamigration.component;
import com.datamigration.support.*;
import com.datamigration.execution.*;

import com.datamigration.api.*;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.model.*;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class TransactionRecoveryTest {
    enum Fault { WRITE_TRANSIENT, COMMIT_BEFORE, COMMIT_AFTER, ROLLBACK_FAILED, VERIFY_UNAVAILABLE }

    @Test void auditWriteFailureRollsBackBusinessRows() throws Exception {
        try (var db=new DatabaseFixture(); var engine=db.engine()) {
            db.seed("A",45);
            db.target("ALTER TABLE migration_audit ADD CONSTRAINT reject_progress CHECK(committed_rows=0)");
            var plan=db.plan(db.task("A"));
            assertThatThrownBy(() -> engine.execute(plan)).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='job' AND state='SUCCEEDED'")).isZero();
            db.target("ALTER TABLE migration_audit DROP CONSTRAINT reject_progress");
            assertThat(engine.execute(plan).tables().get(0).committedRows()).isEqualTo(45);
        }
    }

    @Test void sourceConnectionReceivesOnlySelectStatementsIncludingSequenceFinalization() throws Exception {
        try (var db=new DatabaseFixture()) {
            db.seed("A",230); db.target("CREATE SEQUENCE A_SEQ START WITH 1");
            var statements=new java.util.concurrent.CopyOnWriteArrayList<String>();
            ConnectionAccess traced=new ConnectionAccess() {
                public Connection openTargetConnection() throws SQLException { return db.openTargetConnection(); }
                public Connection openSourceConnection() throws SQLException {
                    Connection real=db.openSourceConnection();
                    return proxy(Connection.class,real,(method,args) -> {
                        if (method.getName().equals("prepareStatement")) statements.add((String)args[0]);
                        if (method.getName().equals("createStatement") || method.getName().equals("prepareCall"))
                            throw new AssertionError("Unexpected source execution path: "+method.getName());
                        return invoke(real,method,args);
                    });
                }
            };
            var original=db.task("A");
            var task=new TableTask(original.name(),original.selectSql(),original.insertSql(),original.fields(),
                    original.targetTable(),original.primaryKey(),2,TableTask.Mode.CLEAR_AND_REIMPORT,
                    new SequenceSpec(true,"A_SEQ","ID"), 100);
            try (var engine=db.engine(traced)) { engine.execute(db.plan(task)); }
            assertThat(statements).isNotEmpty().allMatch(sql -> sql.startsWith("SELECT "));
            assertThat(statements).noneMatch(sql -> sql.contains("NEXTVAL") || sql.contains("NEXT VALUE"));
            try (var connection=db.openSourceConnection(); var query=connection.createStatement();
                 var result=query.executeQuery("SELECT COUNT(*) FROM A")) { result.next(); assertThat(result.getLong(1)).isEqualTo(230); }
        }
    }

    @ParameterizedTest @EnumSource(Fault.class)
    void transactionOutcomeControlsReplay(Fault fault) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 45);
            AtomicBoolean fired = new AtomicBoolean();
            AtomicBoolean unavailable = new AtomicBoolean();
            ConnectionAccess access = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    if (unavailable.get()) throw new SQLException("verification unavailable", "08006");
                    Connection real = db.openTargetConnection();
                    AtomicBoolean business = new AtomicBoolean();
                    AtomicBoolean rollbackFailure = new AtomicBoolean();
                    return proxy(Connection.class, real, (method, args) -> {
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO A(")) {
                            PreparedStatement statement = (PreparedStatement) invoke(real, method, args);
                            return proxy(PreparedStatement.class, statement, (operation, values) -> {
                                if (operation.getName().equals("executeBatch")) {
                                    business.set(true);
                                    if ((fault == Fault.WRITE_TRANSIENT || fault == Fault.ROLLBACK_FAILED) && fired.compareAndSet(false, true)) {
                                        rollbackFailure.set(fault == Fault.ROLLBACK_FAILED);
                                        throw new SQLTransientException("write interrupted", "40001");
                                    }
                                }
                                return invoke(statement, operation, values);
                            });
                        }
                        if (method.getName().equals("rollback") && rollbackFailure.get())
                            throw new SQLException("rollback outcome unavailable", "08006");
                        if (method.getName().equals("commit") && business.get() && fired.compareAndSet(false, true)) {
                            if (fault == Fault.COMMIT_BEFORE) real.rollback();
                            else real.commit();
                            if (fault == Fault.VERIFY_UNAVAILABLE) unavailable.set(true);
                            throw new SQLTransientConnectionException("commit response lost", "08006");
                        }
                        return invoke(real, method, args);
                    });
                }
            };
            var plan = db.plan(db.task("A"));
            try (var engine = db.engine(access)) {
                if (fault == Fault.ROLLBACK_FAILED || fault == Fault.VERIFY_UNAVAILABLE) {
                    var failure = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
                    assertThat(failure.result().tables().get(0).status()).isEqualTo(
                            fault == Fault.VERIFY_UNAVAILABLE ? TableResult.Status.COMMIT_UNKNOWN : TableResult.Status.FAILED);
                    assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(fault == Fault.VERIFY_UNAVAILABLE ? 45 : 0);
                } else {
                    var result = engine.execute(plan).tables().get(0);
                    assertThat(result.committedRows()).isEqualTo(45);
                    assertThat(result.retries()).isEqualTo(fault == Fault.COMMIT_AFTER ? 0 : 1);
                }
            }
            assertThat(fired).isTrue();
            try (var resumed = db.engine()) { resumed.execute(plan); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(45);
            assertThat(db.scalar("SELECT committed_rows FROM migration_audit WHERE job_id='job' AND batch_no=0")).isEqualTo(45);
        }
    }

    @Test void finalizationRetryDoesNotReinsertCommittedRows() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 12);
            var original = db.task("A");
            var task = new TableTask(original.name(), original.selectSql(), original.insertSql(), original.fields(),
                    original.targetTable(), original.primaryKey(), 1, original.mode(), new SequenceSpec(true, "A_SEQ", "ID"), 100);
            var plan = db.plan(task);
            var failure = catchThrowableOfType(() -> engine.execute(plan), MigrationException.class);
            assertThat(failure.result().tables().get(0).status()).isEqualTo(TableResult.Status.FINALIZATION_FAILED);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(12);
            db.target("CREATE SEQUENCE A_SEQ START WITH 1");
            assertThat(engine.execute(plan).tables().get(0).status()).isEqualTo(TableResult.Status.SUCCEEDED);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(12);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(13);
        }
    }

    @FunctionalInterface interface Invocation { Object call(Method method, Object[] args) throws Throwable; }
    static <T> T proxy(Class<T> type, T delegate, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.call(method, args)));
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
