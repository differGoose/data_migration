package com.datamigration.runner;

import com.datamigration.api.MigrationService;
import com.datamigration.autoconfigure.MigrationAutoConfiguration;
import com.datamigration.autoconfigure.MigrationConnectionPools;
import com.datamigration.properties.MigrationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import java.sql.JDBCType;
import static org.assertj.core.api.Assertions.assertThat;

class ExampleConfigurationTest {
    private ApplicationContextRunner runner() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("example", new ClassPathResource("migration.yml"));
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class))
            .withInitializer(context -> yaml.forEach(context.getEnvironment().getPropertySources()::addLast));
    }

    @Test void disabledExampleNeedsNoCredentialsOrConnections() throws Exception {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MigrationService.class);
            assertThat(context.getBeanFactory().containsSingleton("migrationConnectionPools")).isFalse();
        });
    }

    @Test void actualExampleBindsOrderedFieldsAndEnvironmentPlaceholders() throws Exception {
        runner().withPropertyValues(
            "MIGRATION_SOURCE_URL=jdbc:oracle:thin:@localhost:1521/FREEPDB1",
            "MIGRATION_SOURCE_USERNAME=source", "MIGRATION_SOURCE_PASSWORD=test-source",
            "MIGRATION_TARGET_URL=jdbc:postgresql://localhost:5432/target",
            "MIGRATION_TARGET_USERNAME=target", "MIGRATION_TARGET_PASSWORD=test-target")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(MigrationConfiguration.class).get();
                assertThat(properties.source().username()).isEqualTo("source");
                assertThat(properties.toPlan().tables().get(0).fields())
                    .extracting(field -> field.targetJdbcType())
                    .containsExactly(JDBCType.BIGINT, JDBCType.VARCHAR, JDBCType.TIMESTAMP);
                assertThat(properties.strategy().commitSize()).isEqualTo(2000);
                assertThat(context.getBean(MigrationConnectionPools.class).isInitialized()).isFalse();
            });
    }
}
