package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.autoconfigure.MigrationAutoConfiguration;
import com.datamigration.properties.MigrationConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

class MigrationAutoConfigurationTest {
    final ApplicationContextRunner base = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class));
    static String[] properties() { return new String[]{
        "data-migration.job-id=test-job", "data-migration.tables[0].name=a",
        "data-migration.tables[0].target-table=A", "data-migration.tables[0].select-sql=SELECT ID FROM A",
        "data-migration.tables[0].insert-sql=INSERT INTO A VALUES (?)", "data-migration.tables[0].primary-key=ID",
        "data-migration.tables[0].fields[0].source-column=ID", "data-migration.tables[0].fields[0].target-jdbc-type=BIGINT"}; }
    @Test void serviceIsLazyAndDoesNotStartMigrationOrCreateHostInfrastructure() {
        base.run(c -> {
            assertThat(c).hasSingleBean(MigrationService.class).doesNotHaveBean(org.springframework.boot.ApplicationRunner.class)
                .doesNotHaveBean(DataSource.class);
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).hasMessageContaining("Missing data-migration");
        });
    }
    @ParameterizedTest @ValueSource(strings={
        "data-migration.run-on-startup=true", "data-migration.source.jdbc-url=jdbc:h2:mem:x",
        "data-migration.target.username=sa", "data-migration.strategy.fetch-size=1", "data-migration.strategy.commit-size=1",
        "data-migration.strategy.batch-size=1", "data-migration.tables[0].batch-size=0", "data-migration.tables[0].worker-count=0",
        "data-migration.strategy.skip-limit=-1", "data-migration.strategy.transient-retry-count=-1",
        "data-migration.strategy.max-concurrent-tables=0", "data-migration.strategy.max-batch-bytes=0B",
        "data-migration.strategy.max-lob-size=0B", "data-migration.strategy.audit.enabled=false",
        "data-migration.tables[0].primary-key=ID,id", "data-migration.tables[0].sequence.enabled=true",
        "data-migration.strategy.retry-initial-backoff=0s", "data-migration.strategy.retry-max-backoff=1ms",
        "data-migration.tables[0].fields[0].target-jdbc-type=INVALID", "data-migration.tables[0].enabled=false",
        "data-migration.strategy.audit.table-name=unsafe;sql", "data-migration.tables[0].fields="})
    void invalidAndRemovedPropertiesAreRejected(String property) {
        base.withPropertyValues(properties()).withPropertyValues(property).run(c ->
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate())
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class));
    }
    @Configuration(proxyBeanMethods=false) static class Host {
        @Bean @MigrationSource(capacity=4) DataSource s() { return new DriverManagerDataSource("jdbc:h2:mem:s", "sa", ""); }
        @Bean @MigrationTarget(capacity=4) DataSource t() { return new DriverManagerDataSource("jdbc:h2:mem:t", "sa", ""); }
        @Bean DataSourceTransactionManager hostTransactions(DataSource s) { return new DataSourceTransactionManager(s); }
    }
    @Configuration(proxyBeanMethods=false) static class Duplicate {
        @Bean @MigrationSource(capacity=4) DataSource another() { return new DriverManagerDataSource(); }
    }
    @Configuration(proxyBeanMethods=false) static class InvalidCapacity {
        @Bean @MigrationSource(capacity=0) DataSource invalid() { return new DriverManagerDataSource(); }
    }
    @MigrationSource(capacity=4)
    static class SourceType extends DriverManagerDataSource {
        SourceType() { super("jdbc:h2:mem:type_source", "sa", ""); }
    }
    @Configuration(proxyBeanMethods=false) static class TypeHost {
        @Bean SourceType sourceByType() { return new SourceType(); }
        @Bean @MigrationTarget(capacity=4) DataSource targetByMethod() {
            return new DriverManagerDataSource("jdbc:h2:mem:type_target", "sa", "");
        }
        @Bean DataSource unrelatedBusinessDataSource() { return new DriverManagerDataSource(); }
    }
    @Test void implementationTypeAnnotationsSelectOnlyMigrationSources() {
        base.withPropertyValues(properties()).withUserConfiguration(TypeHost.class).run(c -> {
            assertThat(c.getBeansOfType(DataSource.class)).hasSize(3);
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).isInstanceOf(MigrationException.class);
        });
    }
    @Test void missingDuplicateAndInvalidRoleDeclarationsFail() {
        base.withPropertyValues(properties()).run(c -> assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).hasMessageContaining("MigrationSource"));
        base.withPropertyValues(properties()).withUserConfiguration(Host.class, Duplicate.class).run(c ->
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).hasMessageContaining("exactly one"));
        base.withPropertyValues(properties()).withUserConfiguration(InvalidCapacity.class).run(c ->
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).hasMessageContaining("positive"));
    }
    @Test void hostBeansArePreservedAndAnnotatedConnectionsUsed() {
        base.withPropertyValues(properties()).withUserConfiguration(Host.class).run(c -> {
            assertThat(c.getBeansOfType(DataSource.class)).hasSize(2);
            assertThat(c).hasSingleBean(DataSourceTransactionManager.class);
            assertThatThrownBy(() -> c.getBean(MigrationService.class).migrate()).isInstanceOf(MigrationException.class);
            var properties=c.getBean(MigrationConfiguration.class).get();
            assertThat(properties.tables().get(0).batchSize()).isEqualTo(1000);
            assertThat(properties.toString()).doesNotContain("SELECT");
            assertThat(properties.tables().get(0).toString()).doesNotContain("SELECT");
        });
    }
    @Test void customServiceAndEngineCanOverrideDefaults() {
        MigrationService service=() -> new MigrationResult(java.util.UUID.randomUUID(),java.time.Instant.now(),java.time.Instant.now(),java.util.List.of());
        base.withBean(MigrationService.class, () -> service).run(c -> assertThat(c.getBean(MigrationService.class)).isSameAs(service));
        base.withPropertyValues(properties()).withBean(com.datamigration.execution.MigrationEngine.class, () -> plan -> service.migrate())
            .run(c -> assertThat(c.getBean(MigrationService.class).migrate()).isNotNull());
    }
}
