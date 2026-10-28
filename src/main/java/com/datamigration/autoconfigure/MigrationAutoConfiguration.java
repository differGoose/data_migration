package com.datamigration.autoconfigure;

import com.datamigration.api.MigrationService;
import com.datamigration.execution.MigrationEngine;
import com.datamigration.execution.JdbcMigrationEngine;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.properties.MigrationConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

@AutoConfiguration
public class MigrationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ValueConverter.class)
    ValueConverter migrationValueConverter() { return new JdbcValueConverter(); }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(MigrationEngine.class)
    JdbcMigrationEngine migrationEngine(MigrationConfiguration configuration, MigrationConnectionPools pools,
                                        ValueConverter converter) {
        var properties = configuration.get();
        return new JdbcMigrationEngine(pools, properties.source().pool().maximumPoolSize(),
                properties.target().pool().maximumPoolSize(),
                properties.source().jdbcUrl() + "|" + properties.source().username() + "|" +
                properties.target().jdbcUrl() + "|" + properties.target().username(), converter);
    }

    @Bean
    @ConditionalOnMissingBean
    MigrationConfiguration migrationConfiguration(Environment environment) {
        return new MigrationConfiguration(environment);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean
    MigrationConnectionPools migrationConnectionPools(MigrationConfiguration configuration) {
        var properties = configuration.get();
        return new MigrationConnectionPools(properties.source(), properties.target());
    }

    @Bean
    @ConditionalOnMissingBean(MigrationService.class)
    MigrationService migrationService(MigrationConfiguration configuration, ObjectProvider<MigrationEngine> engines) {
        return () -> {
            var plan = configuration.get().toPlan();
            MigrationEngine engine = engines.getIfAvailable();
            if (engine == null) {
                throw new IllegalStateException("No MigrationEngine is available; check custom engine bean configuration.");
            }
            return engine.execute(plan);
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-migration", name = "run-on-startup", havingValue = "true")
    ApplicationRunner migrationStartupRunner(MigrationService service) {
        return arguments -> service.migrate();
    }
}
