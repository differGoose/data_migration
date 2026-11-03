package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.autoconfigure.MigrationAutoConfiguration;
import com.datamigration.execution.ConnectionBudget;
import com.datamigration.jdbc.PoolCapacity;
import com.datamigration.properties.MigrationConfiguration;
import com.datamigration.support.DatabaseFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static com.datamigration.support.MigrationAwait.await;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolCapacityTest {
    MockEnvironment configuration(String... names) { return new AsyncMigrationTest().configuration(names); }
    HikariDataSource pool(String url, int maximum) {
        var pool = new HikariDataSource(); pool.setJdbcUrl(url); pool.setUsername("sa"); pool.setPassword("");
        pool.setMaximumPoolSize(maximum); pool.setMinimumIdle(0); pool.setConnectionTimeout(1000);
        return pool;
    }
    MigrationRunner.Builder runner(DataSource source, DataSource target, MockEnvironment env) {
        return MigrationRunner.builder().source(source).target(target).environment(env);
    }
    @ParameterizedTest @CsvSource({"4,12", "2,6", "1,3"})
    void budgetFollowsTableConcurrencyAndWorkerCounts(int parallel, int required) {
        var env = configuration("A", "B", "C", "D").withProperty("data-migration.strategy.max-concurrent-tables", ""+parallel);
        for (int i=0;i<4;i++) env.withProperty("data-migration.tables["+i+"].worker-count", "2");
        var budget = ConnectionBudget.forPlan(new MigrationConfiguration(env).get().toPlan());
        assertThat(budget.source()).isEqualTo(required); assertThat(budget.target()).isEqualTo(required);
    }
    @Test void fourTablesCompleteAtAutomaticMinimumWithoutChangingPoolMaximums() throws Exception {
        try (var db = new DatabaseFixture(); var source = pool(db.sourceUrl,12); var target = pool(db.targetUrl,12)) {
            for (String name : new String[]{"A","B","C","D"}) db.seed(name,3);
            var env=configuration("A","B","C","D").withProperty("data-migration.strategy.max-concurrent-tables","4");
            for (int i=0;i<4;i++) env.withProperty("data-migration.tables["+i+"].worker-count","2");
            try (var migration=runner(source,target,env).build()) { assertThat(await(migration).tables()).hasSize(4); }
            assertThat(source.getMaximumPoolSize()).isEqualTo(12); assertThat(target.getMaximumPoolSize()).isEqualTo(12);
            assertThat(source.isClosed() || target.isClosed()).isFalse();
        }
    }
    @ParameterizedTest @CsvSource({"source,1,4", "target,4,1"})
    void insufficientPoolFailsBeforeBorrowAndNamesEndpointAndBothCounts(String endpoint,int sourceMax,int targetMax) throws Exception {
        try (var db=new DatabaseFixture(); var source=spy(pool(db.sourceUrl,sourceMax)); var target=spy(pool(db.targetUrl,targetMax));
             var migration=runner(source,target,configuration("A")).build()) {
            assertThatThrownBy(migration::migrate).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Insufficient "+endpoint+" pool capacity; required=2, maximumPoolSize=1");
            verify(source,never()).getConnection(); verify(target,never()).getConnection();
        }
    }
    @Test void automaticFailureCanRetryAfterHostFixesMaximum() throws Exception {
        try (var db=new DatabaseFixture();var source=pool(db.sourceUrl,2);var target=pool(db.targetUrl,1);
             var migration=runner(source,target,configuration("A")).build()) {
            db.seed("A",10);
            var failed=migration.migrateAsync(); assertThatThrownBy(() -> await(failed)).hasMessageContaining("target pool capacity");
            assertThat(failed.progress().status()).isEqualTo(MigrationProgress.Status.FAILED);
            target.setMaximumPoolSize(2);
            assertThat(await(migration).tables().get(0).committedRows()).isEqualTo(10);
        }
    }
    @Test void actualLimitsAreRecheckedAfterFailedDataTransfer() throws Exception {
        try (var db=new DatabaseFixture();var source=pool(db.sourceUrl,4);var target=pool(db.targetUrl,4);
             var migration=runner(source,target,configuration("A")).build()) {
            db.seed("A",20); db.target("ALTER TABLE A ADD CONSTRAINT fail_once CHECK(ID<>9)");
            assertThatThrownBy(migration::migrate).isInstanceOf(MigrationException.class);
            db.target("ALTER TABLE A DROP CONSTRAINT fail_once");
            target.setMaximumPoolSize(1);
            assertThatThrownBy(migration::migrate).hasMessageContaining("required=2, maximumPoolSize=1");
            target.setMaximumPoolSize(2);
            assertThat(await(migration).tables().get(0).committedRows()).isEqualTo(20);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
        }
    }
    @Test void sharedUnderlyingPoolNeedsCombinedSourceAndTargetCapacity() throws Exception {
        try (var db=new DatabaseFixture();var pool=spy(pool(db.targetUrl,3))) {
            db.target("CREATE TABLE SOURCE_A(ID BIGINT PRIMARY KEY,NAME VARCHAR(20))");
            db.target("INSERT INTO SOURCE_A VALUES(1,'one'),(2,'two')");
            db.target("CREATE TABLE A(ID BIGINT PRIMARY KEY,NAME VARCHAR(20))");
            var env=configuration("A").withProperty("data-migration.tables[0].select-sql","SELECT ID,NAME FROM SOURCE_A");
            try(var migration=runner(new DelegatingDataSource(pool),new DelegatingDataSource(pool),env).build()) {
                assertThatThrownBy(migration::migrate).hasMessage("Insufficient shared pool capacity; required=4, maximumPoolSize=3");
                verify(pool,never()).getConnection();
                pool.setMaximumPoolSize(4);
                assertThat(await(migration).tables().get(0).committedRows()).isEqualTo(2);
            }
        }
    }
    @Test void standardJdbcUnwrapAndSpringDelegationExposePoolWithoutBorrowing() throws Exception {
        try(var pool=spy(pool("jdbc:invalid:secret",5))) {
            var wrapper=mock(DataSource.class);
            when(wrapper.isWrapperFor(HikariDataSource.class)).thenReturn(true);
            when(wrapper.unwrap(HikariDataSource.class)).thenReturn(pool);
            var actual=PoolCapacity.resolve(new DelegatingDataSource(wrapper),"source",null);
            assertThat(actual.capacity()).isEqualTo(5); assertThat(actual.pool()).isSameAs(pool);
            verify(wrapper,never()).getConnection(); verify(pool,never()).getConnection();
        }
    }
    @Test void unknownWrappersAndCyclesFailRatherThanGuessButExplicitFallbackRemains() throws Exception {
        var unknown=mock(DataSource.class);
        when(unknown.isWrapperFor(any())).thenThrow(new SQLException("private-password-token"));
        assertThatThrownBy(() -> PoolCapacity.resolve(unknown,"source",null))
                .hasMessage("Cannot determine source pool capacity; provide a supported pool or explicit migration capacity")
                .hasNoCause();
        assertThat(PoolCapacity.resolve(unknown,"source",3).capacity()).isEqualTo(3);
        var first=new DelegatingDataSource();var second=new DelegatingDataSource();
        first.setTargetDataSource(second);second.setTargetDataSource(first);
        assertThatThrownBy(() -> PoolCapacity.resolve(first,"target",null)).hasMessageContaining("Cannot determine target pool capacity");
        assertThatThrownBy(() -> PoolCapacity.resolve(new DelegatingDataSource(),"source",null)).hasMessageContaining("Cannot determine source pool capacity");
        verify(unknown,never()).getConnection();
    }
    @Test void unsetAndUnreadablePoolMaximumFailWithoutLeakingGetterException() {
        try(var unset=new HikariDataSource();var broken=spy(new HikariDataSource())) {
            assertThat(PoolCapacity.resolve(unset,"source",null).capacity()).isEqualTo(unset.getMaximumPoolSize());
            doReturn(-1).when(broken).getMaximumPoolSize();
            assertThatThrownBy(() -> PoolCapacity.resolve(broken,"source",null)).hasMessageContaining("configure a positive pool maximum");
            doThrow(new IllegalStateException("private-password-token")).when(broken).getMaximumPoolSize();
            assertThatThrownBy(() -> PoolCapacity.resolve(broken,"target",null))
                    .hasMessage("Cannot read target maximumPoolSize; configure a positive pool maximum before migration").hasNoCause();
        }
    }
    @Test void explicitDeclarationCannotHideSmallerActualHikariPool() throws Exception {
        try(var db=new DatabaseFixture();var source=pool(db.sourceUrl,1);var target=pool(db.targetUrl,4);
            var migration=MigrationRunner.builder().source(source,100).target(target,100).environment(configuration("A")).build()) {
            assertThatThrownBy(migration::migrate).hasMessage("Insufficient source pool capacity; required=2, maximumPoolSize=1");
        }
    }
    @Test void keylessBudgetIsOneSourceAndTwoTargets() throws Exception {
        var env=configuration("A");
        env.getPropertySources().forEach(source -> { if(source instanceof MapPropertySource map) map.getSource().remove("data-migration.tables[0].primary-key[0]"); });
        try(var db=new DatabaseFixture();var source=pool(db.sourceUrl,1);var target=pool(db.targetUrl,2);
            var migration=runner(source,target,env).build()) {
            db.seed("A",20);assertThat(await(migration).tables().get(0).committedRows()).isEqualTo(20);
        }
    }
    @Test void unpooledSourcesCanContinueUsingExplicitCapacity() throws Exception {
        try(var db=new DatabaseFixture();var migration=MigrationRunner.builder()
                .source(new DriverManagerDataSource(db.sourceUrl,"sa",""),2)
                .target(new DriverManagerDataSource(db.targetUrl,"sa",""),2).environment(configuration("A")).build()) {
            db.seed("A",5); assertThat(await(migration).tables().get(0).committedRows()).isEqualTo(5);
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean @MigrationSource HikariDataSource source(org.springframework.core.env.Environment env) { return create(env.getRequiredProperty("pool-test.source")); }
        @Bean @MigrationTarget HikariDataSource target(org.springframework.core.env.Environment env) { return create(env.getRequiredProperty("pool-test.target")); }
        static HikariDataSource create(String url) { var pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername("sa");pool.setMaximumPoolSize(2);pool.setMinimumIdle(0);return pool; }
    }
    @Test void springAnnotationsDefaultToAutomaticPoolValidation() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",6);var env=configuration("A");
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class)).withUserConfiguration(Host.class)
                .withPropertyValues("pool-test.source="+db.sourceUrl,"pool-test.target="+db.targetUrl)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(env.getPropertySources().get("mockProperties")))
                .run(context -> assertThat(await(context.getBean(MigrationService.class)).tables().get(0).committedRows()).isEqualTo(6));
        }
    }
    @Test void automaticCapacityErrorsExposeSafeCountsAndNeverPoolSecrets() throws Exception {
        String secret="private-password-token";
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.datamigration");
        var previous=logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.INFO);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();logger.addAppender(capture);
        try(var source=spy(pool("jdbc:invalid:"+secret,1));var target=pool("jdbc:invalid:"+secret,4)) {
            try(var migration=runner(source,target,configuration("A")).build()) {
                assertThatThrownBy(migration::migrate).hasMessageContaining("required=2, maximumPoolSize=1");
            }
            doThrow(new IllegalStateException(secret)).when(source).getMaximumPoolSize();
            try(var migration=runner(source,target,configuration("A")).build()) {
                assertThatThrownBy(migration::migrate).hasMessageContaining("Cannot read source maximumPoolSize");
            }
            try(var migration=runner(mock(DataSource.class),target,configuration("A")).build()) {
                assertThatThrownBy(migration::migrate).hasMessageContaining("Cannot determine source pool capacity");
            }
            String logs=capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("stage=CAPACITY_CHECK endpoint=source", "reason=Insufficient source pool capacity; required=2, maximumPoolSize=1",
                    "reason=Cannot read source maximumPoolSize", "reason=Cannot determine source pool capacity").doesNotContain(secret);
            assertThat(capture.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(source,never()).getConnection();
        } finally { logger.detachAppender(capture);capture.stop();logger.setLevel(previous); }
    }

}
