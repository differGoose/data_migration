package com.datamigration.component;

import static com.datamigration.support.MigrationAwait.await;

import com.datamigration.api.*;
import com.datamigration.autoconfigure.MigrationAutoConfiguration;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.execution.*;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class GlobalMigrationExecutionTest {
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean @MigrationSource(capacity=3) DataSource source(Environment env) {
            return new DriverManagerDataSource(env.getRequiredProperty("test.source"),"sa","");
        }
        @Bean @MigrationTarget(capacity=3) DataSource target(Environment env) {
            return new DriverManagerDataSource(env.getRequiredProperty("test.target"),"sa","");
        }
    }
    ApplicationContextRunner context(DatabaseFixture db) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class))
            .withUserConfiguration(Host.class).withPropertyValues(
                "test.source="+db.sourceUrl,"test.target="+db.targetUrl,"data-migration.job-id=contexts",
                "data-migration.tables[0].name=B","data-migration.tables[0].target-table=B",
                "data-migration.tables[0].select-sql=SELECT ID,NAME FROM B",
                "data-migration.tables[0].insert-sql=INSERT INTO B VALUES (?,?)",
                "data-migration.tables[0].primary-key[0]=ID",
                "data-migration.tables[0].fields[0].source-column=ID",
                "data-migration.tables[0].fields[0].target-jdbc-type=BIGINT",
                "data-migration.tables[0].fields[1].source-column=NAME",
                "data-migration.tables[0].fields[1].target-jdbc-type=VARCHAR");
    }
    @Test void runnersAndSpringContextsSharePermitUntilInterruptedWorkersFinishCleanup() throws Exception {
        try (var db=new DatabaseFixture()) {
            db.seed("A",10); db.seed("B",10);
            var executor=Executors.newSingleThreadExecutor();
            var entered=new CountDownLatch(1); var interrupted=new CountDownLatch(1); var release=new CountDownLatch(1);
            try (var scope=new ExecutorScope(executor);
                 var active=MigrationRunner.builder()
                    .source(new DriverManagerDataSource(db.sourceUrl,"sa",""),3)
                    .target(new DriverManagerDataSource(db.targetUrl,"sa",""),3)
                    .environment(new org.springframework.mock.env.MockEnvironment()
                        .withProperty("spring.config.location","unused"))
                    .build()) {
                // Use the engine's public entry for a controlled worker; all public adapters share it.
                try (var engine=new JdbcMigrationEngine(db,3,3,(value,type) -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) {
                        interrupted.countDown();
                        boolean done=false;
                        while (!done) {
                            try { release.await(); done=true; }
                            catch (InterruptedException ignored) { }
                        } // Simulate a driver still finishing rollback after cancellation.
                        throw new SQLException("cancelled","57014");
                    }
                    return new JdbcValueConverter().convert(value,type);
                })) {
                    var first=executor.submit(() -> engine.execute(db.plan(db.task("A"))));
                    try {
                        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                        context(db).run(one -> context(db).run(two -> {
                            assertThatThrownBy(() -> await(one.getBean(MigrationService.class))).hasMessageContaining("already running");
                            assertThatThrownBy(() -> await(two.getBean(MigrationService.class))).hasMessageContaining("already running");
                        }));
                        active.close(); // An unused Runner cannot release another owner's permit.
                        first.cancel(true);
                        assertThat(interrupted.await(5,TimeUnit.SECONDS)).isTrue();
                        try (var next=db.engine()) {
                            assertThatThrownBy(() -> next.execute(db.plan(db.task("B")))).hasMessageContaining("already running");
                        }
                    } finally { release.countDown(); }
                }
            }
            context(db).run(c -> assertThat(await(c.getBean(MigrationService.class)).tables().get(0).committedRows()).isEqualTo(10));
        }
    }
    private com.zaxxer.hikari.HikariDataSource pool(String url,int capacity) {
        var pool=new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(url); pool.setUsername("sa"); pool.setPassword("");
        pool.setMaximumPoolSize(capacity); pool.setMinimumIdle(0); pool.setConnectionTimeout(1000);
        return pool;
    }
    @Test void exactMinimumBudgetsWorkAndOneLessFailsBeforeDatabaseAccess() throws Exception {
        try (var db=new DatabaseFixture()) {
            db.seed("A",210);
            try (var source=pool(db.sourceUrl,3); var target=pool(db.targetUrl,3);
                 var engine=new JdbcMigrationEngine(new com.datamigration.jdbc.DataSourceConnections(source,target),3,3,new JdbcValueConverter())) {
                assertThat(engine.execute(db.plan(db.task("A"))).tables().get(0).committedRows()).isEqualTo(210);
            }
            try (var engine=new JdbcMigrationEngine(db,3,2,new JdbcValueConverter())) {
                assertThatThrownBy(() -> engine.execute(db.plan(db.task("A")))).hasMessageContaining("target requires 3");
            }
            var t=db.task("A");
            var keyless=new com.datamigration.model.TableTask(t.name(),t.selectSql(),t.insertSql(),t.fields(),t.targetTable(),
                java.util.List.of(),1,com.datamigration.model.TableTask.Mode.CLEAR_AND_REIMPORT,t.sequence(),17);
            try (var source=pool(db.sourceUrl,1); var target=pool(db.targetUrl,2);
                 var engine=new JdbcMigrationEngine(new com.datamigration.jdbc.DataSourceConnections(source,target),1,2,new JdbcValueConverter())) {
                assertThat(engine.execute(db.plan("keyless",0,keyless)).tables().get(0).committedRows()).isEqualTo(210);
            }
            try (var engine=new JdbcMigrationEngine(db,1,1,new JdbcValueConverter())) {
                assertThatThrownBy(() -> engine.execute(db.plan("keyless",0,keyless))).hasMessageContaining("target requires 2");
            }
        }
    }
}
