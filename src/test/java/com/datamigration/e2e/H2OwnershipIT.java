package com.datamigration.e2e;

import com.datamigration.api.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.execution.JdbcMigrationEngine;
import com.datamigration.execution.ExecutorScope;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class H2OwnershipIT {
    @TempDir Path directory;
    enum Pause { BEFORE_CLEAR, INSIDE_DELETE, SEQUENCE }

    @ParameterizedTest @EnumSource(Pause.class)
    void newReimportSurvivesOldCoordinatorAfterGuardLoss(Pause point) throws Exception {
        var callers=Executors.newFixedThreadPool(2);
        try (var databases=new TcpDatabases(directory.resolve("db"),false);
             var callerScope=new ExecutorScope(callers)) {
            databases.seed(10_000);
            DatabaseFixture.execute(databases.targetUrl(),"INSERT INTO CUSTOMER VALUES(99999,'old',1)");
            DatabaseFixture.execute(databases.targetUrl(),"CREATE SEQUENCE CUSTOMER_SEQ START WITH 1");
            Path yaml=directory.resolve("migration.yml");
            try (var resource=getClass().getResourceAsStream("/recovery.yml")) {
                Files.writeString(yaml,new String(resource.readAllBytes(),StandardCharsets.UTF_8)
                        .replace("worker-count: 1","worker-count: 1\n      mode: CLEAR_AND_REIMPORT\n      sequence:\n        enabled: true\n        name: CUSTOMER_SEQ\n        target-column: ID"));
            }
            CountDownLatch paused=new CountDownLatch(1), release=new CountDownLatch(1);
            AtomicReference<Connection> guard=new AtomicReference<>();
            AtomicBoolean once=new AtomicBoolean();
            ConnectionAccess faulty=new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return wrap(DatabaseFixture.connection(databases.sourceUrl()),true); }
                public Connection openTargetConnection() throws SQLException { return wrap(DatabaseFixture.connection(databases.targetUrl()),false); }
                Connection wrap(Connection real,boolean source) {
                    return proxy(Connection.class,(method,args) -> {
                        Object result=invoke(real,method,args);
                        if (!source && point==Pause.SEQUENCE && method.getName().equals("createStatement")) {
                            Statement statement=(Statement)result;
                            return proxy(Statement.class,(operation,values) -> {
                                Object outcome=invoke(statement,operation,values);
                                if (operation.getName().equals("executeQuery") && ((String)values[0]).startsWith("SELECT MAX(")
                                        && once.compareAndSet(false,true)) {
                                    paused.countDown();
                                    if (!release.await(20,TimeUnit.SECONDS)) throw new SQLException("Test release timed out");
                                }
                                return outcome;
                            });
                        }
                        if (method.getName().equals("prepareStatement")) {
                            String sql=(String)args[0];
                            if (!source && sql.contains("FOR UPDATE NOWAIT")) guard.set(real);
                            boolean intercept=point==Pause.INSIDE_DELETE ? !source && sql.startsWith("DELETE FROM CUSTOMER")
                                    : point==Pause.BEFORE_CLEAR && source && sql.startsWith("SELECT COUNT(*)");
                            if (intercept) {
                                PreparedStatement statement=(PreparedStatement)result;
                                return proxy(PreparedStatement.class,(operation,values) -> {
                                    Object outcome=invoke(statement,operation,values);
                                    if (operation.getName().startsWith("execute") && once.compareAndSet(false,true)) {
                                        paused.countDown();
                                        if (!release.await(20,TimeUnit.SECONDS)) throw new SQLException("Test release timed out");
                                    }
                                    return outcome;
                                });
                            }
                        }
                        return result;
                    });
                }
            };
            try (var oldEngine=new JdbcMigrationEngine(faulty,6,6,"old",new JdbcValueConverter());
                 var oldContext=new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties())
                         .initializers(app -> app.getBeanFactory().registerSingleton("faultEngine",oldEngine))
                         .run("--spring.config.location="+yaml.toUri(),"--data-migration.job-id=old");
                 var newContext=new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties())
                         .run("--spring.config.location="+yaml.toUri(),"--data-migration.job-id=e2e")) {
                Future<?> old=callers.submit(() -> oldContext.getBean(MigrationService.class).migrate());
                try {
                    assertThat(paused.await(10,TimeUnit.SECONDS)).isTrue();
                    String priorToken=token(databases);
                    guard.get().close(); // The coordinator connection and its transaction remain alive.
                    Future<?> replacement=callers.submit(() -> newContext.getBean(MigrationService.class).migrate());
                    if (point!=Pause.BEFORE_CLEAR) {
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                        while (priorToken.equals(token(databases)) && System.nanoTime()<deadline) Thread.sleep(10);
                        assertThat(token(databases)).isNotEqualTo(priorToken);
                        assertThat(replacement.isDone()).isFalse(); // Must wait for old coordinator transaction.
                        release.countDown();
                    }
                    replacement.get(20,TimeUnit.SECONDS);
                    release.countDown();
                    assertThatThrownBy(() -> old.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                    H2MigrationIT.assertEquivalent(databases);
                } finally { release.countDown(); }
            }
        }
    }

    private String token(TcpDatabases databases) throws Exception {
        try (var connection=DatabaseFixture.connection(databases.targetUrl());var statement=connection.createStatement();
             var result=statement.executeQuery("SELECT fingerprint FROM migration_audit WHERE job_id='__table_lock__' AND batch_no=1")) {
            result.next(); return result.getString(1);
        }
    }
    @FunctionalInterface interface Invocation { Object call(Method method,Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type,Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args) -> call.call(method,args)));
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
