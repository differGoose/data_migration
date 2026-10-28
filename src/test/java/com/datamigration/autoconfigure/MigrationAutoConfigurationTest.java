package com.datamigration.autoconfigure;

import com.datamigration.api.*;
import com.datamigration.execution.MigrationEngine;
import com.datamigration.properties.MigrationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MigrationAutoConfigurationTest {
    private final ApplicationContextRunner base = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class));

    private ApplicationContextRunner configured() {
        return base.withPropertyValues(
            "data-migration.job-id=test-job",
            "data-migration.tables[0].target-table=CUSTOMER",
            "data-migration.source.jdbc-url=jdbc:h2:mem:source",
            "data-migration.source.username=sa", "data-migration.source.password=",
            "data-migration.source.driver-class-name=org.h2.Driver",
            "data-migration.target.jdbc-url=jdbc:h2:mem:target",
            "data-migration.target.username=sa", "data-migration.target.password=",
            "data-migration.target.driver-class-name=org.h2.Driver",
            "data-migration.tables[0].name=customer",
            "data-migration.tables[0].select-sql=SELECT ID AS CUSTOMER_ID FROM CUSTOMER",
            "data-migration.tables[0].insert-sql=INSERT INTO CUSTOMER(ID) VALUES (?)",
            "data-migration.tables[0].fields[0].source-column=CUSTOMER_ID",
            "data-migration.tables[0].fields[0].target-jdbc-type=BIGINT");
    }

    @Test void serviceAlwaysAvailableWithoutConfigurationOrPoolCreation() {
        base.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MigrationService.class)
                .doesNotHaveBean(ApplicationRunner.class);
            assertThat(context.getBeanFactory().containsSingleton("migrationConnectionPools")).isFalse();
            assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
                .hasMessageContaining("Missing data-migration configuration");
        });
    }

    @Test void startupFlagRegistersRunnerAndMissingConfigurationFailsOnExecution() {
        base.withPropertyValues("data-migration.run-on-startup=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MigrationService.class)
                .hasSingleBean(ApplicationRunner.class);
            assertThatThrownBy(() -> context.getBean(ApplicationRunner.class)
                .run(new DefaultApplicationArguments())).isInstanceOf(RuntimeException.class);
        });
    }

    @Test void enabledConfigurationIsLazyAndMissingAuditFailsExplicitly() {
        configured().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class)
                .doesNotHaveBean(PlatformTransactionManager.class);
            var pools = context.getBean(MigrationConnectionPools.class);
            assertThat(pools.isInitialized()).isFalse();
            assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
                .isInstanceOf(MigrationException.class);
            assertThat(pools.isInitialized()).isTrue();
            assertThat(context.getBean(MigrationConfiguration.class).get().strategy().skipLimit()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "data-migration.strategy.batch-size=0",
        "data-migration.strategy.commit-size=0",
        "data-migration.strategy.fetch-size=-1",
        "data-migration.strategy.skip-limit=-1",
        "data-migration.strategy.transient-retry-count=-1",
        "data-migration.strategy.max-concurrent-tables=0",
        "data-migration.strategy.max-batch-bytes=0B",
        "data-migration.strategy.max-lob-size=0B",
        "data-migration.strategy.audit.enabled=false",
        "data-migration.source.pool.idle-timeout=1s",
        "data-migration.source.pool.max-lifetime=1s",
        "data-migration.tables[0].worker-count=2",
        "data-migration.tables[0].primary-key=ID,id",
        "data-migration.tables[0].sequence.enabled=true",
        "data-migration.strategy.retry-initial-backoff=0s",
        "data-migration.strategy.retry-max-backoff=1ms",
        "data-migration.source.jdbc-url=not-jdbc",
        "data-migration.source.username=",
        "data-migration.source.pool.minimum-idle=5",
        "data-migration.target.pool.connection-timeout=1ms",
        "data-migration.tables[0].fields[0].target-jdbc-type=INVALID",
        "data-migration.tables[0].fields[0].source-column=",
        "data-migration.tables[0].enabled=false",
        "data-migration.strategy.audit.table-name=unsafe;sql",
        "data-migration.strategy.batch-szie=100",
        "data-migration.enabled=true"
    })
    void rejectsInvalidConfiguration(String property) {
        configured().withPropertyValues(property).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MigrationService.class);
            assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
        });
    }

    @Test void rejectsMissingEndpointsAndFields() {
        base.run(context -> assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
            .hasMessageContaining("Missing data-migration configuration"));
        configured().withPropertyValues("data-migration.tables[0].fields=")
            .run(context -> assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class));
    }

    @Test void rejectsDuplicateTaskNames() {
        configured().withPropertyValues(
            "data-migration.tables[1].name=CUSTOMER",
            "data-migration.tables[1].target-table=OTHER_CUSTOMER",
            "data-migration.tables[1].select-sql=SELECT ID FROM CUSTOMER",
            "data-migration.tables[1].insert-sql=INSERT INTO CUSTOMER(ID) VALUES (?)",
            "data-migration.tables[1].fields[0].source-column=ID",
            "data-migration.tables[1].fields[0].target-jdbc-type=BIGINT")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate())
                    .hasStackTraceContaining("table names must be unique");
            });
    }

    @Test void preservesHostDataSourceAndTransactionManager() {
        DataSource host = mock(DataSource.class);
        PlatformTransactionManager transactions = new DataSourceTransactionManager(host);
        configured().withBean("hostDataSource", DataSource.class, () -> host)
            .withBean("hostTransactions", PlatformTransactionManager.class, () -> transactions)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(DataSource.class)
                    .hasSingleBean(PlatformTransactionManager.class);
                assertThat(context.getBean(DataSource.class)).isSameAs(host);
                assertThat(context.getBean(PlatformTransactionManager.class)).isSameAs(transactions);
                assertThat(context.getBean(MigrationConnectionPools.class).isInitialized()).isFalse();
            });
    }

    @Test void customEngineReceivesPlan() {
        AtomicInteger calls = new AtomicInteger();
        MigrationEngine engine = plan -> {
            calls.incrementAndGet();
            assertThat(plan.tables()).hasSize(1);
            assertThat(plan.tables().getFirst().fields().getFirst().sourceColumn()).isEqualTo("CUSTOMER_ID");
            return result();
        };
        configured().withBean(MigrationEngine.class, () -> engine).run(context -> {
            assertThat(calls).hasValue(0);
            assertThat(context.getBean(MigrationService.class).migrate()).isNotNull();
            assertThat(calls).hasValue(1);
        });
    }

    @Test void customServiceOverridesDefaultAndStartupRunsSynchronously() {
        AtomicInteger calls = new AtomicInteger();
        MigrationService service = () -> { calls.incrementAndGet(); return result(); };
        configured().withPropertyValues("data-migration.run-on-startup=true")
            .withBean(MigrationService.class, () -> service).run(context -> {
                assertThat(context).hasSingleBean(MigrationService.class).hasSingleBean(ApplicationRunner.class);
                assertThat(context.getBean(MigrationService.class)).isSameAs(service);
                context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
                assertThat(calls).hasValue(1);
            });
    }

    @Test void startupFailurePropagatesWithoutConnections() {
        configured().withPropertyValues("data-migration.run-on-startup=true").run(context -> {
            assertThatThrownBy(() -> context.getBean(ApplicationRunner.class)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(MigrationException.class);
            assertThat(context.getBean(MigrationConnectionPools.class).isInitialized()).isTrue();
        });
    }

    @Test void poolsAreSeparateAndClosedWithContext() {
        MigrationConnectionPools[] holder = new MigrationConnectionPools[1];
        configured().run(context -> {
            var pools = context.getBean(MigrationConnectionPools.class);
            holder[0] = pools;
            try (var source = pools.openSourceConnection(); var target = pools.openTargetConnection()) {
                assertThat(source.getMetaData().getURL()).isEqualTo("jdbc:h2:mem:source");
                assertThat(target.getMetaData().getURL()).isEqualTo("jdbc:h2:mem:target");
                assertThat(target.getAutoCommit()).isFalse();
                assertThat(pools.isInitialized()).isTrue();
            }
        });
        assertThatThrownBy(() -> holder[0].openSourceConnection()).hasMessageContaining("closed");
        assertThatThrownBy(() -> holder[0].openTargetConnection()).hasMessageContaining("closed");
        holder[0].close(); // Idempotent.
    }

    @Test void diagnosticsDoNotPrintCredentialsOrSql() {
        configured().run(context -> {
            var p = context.getBean(MigrationConfiguration.class).get();
            assertThat(p.toString()).isEqualTo("MigrationProperties[redacted]");
            assertThat(p.source().toString()).isEqualTo("Endpoint[redacted]");
            assertThat(p.tables().getFirst().toString()).doesNotContain("SELECT", "INSERT");
        });
    }

    private MigrationResult result() {
        return new MigrationResult(UUID.randomUUID(), Instant.now(), Instant.now(), List.of());
    }
}
