package com.datamigration.autoconfigure;

import com.datamigration.api.*;
import com.datamigration.conversion.*;
import com.datamigration.execution.MigrationEngine;
import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.properties.MigrationConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.*;

@AutoConfiguration
public class MigrationAutoConfiguration {
    @Bean @ConditionalOnMissingBean(ValueConverter.class)
    ValueConverter migrationValueConverter() { return new JdbcValueConverter(); }
    @Bean @ConditionalOnMissingBean
    MigrationConfiguration migrationConfiguration(Environment environment) { return new MigrationConfiguration(environment); }
    @Bean @Lazy @ConditionalOnMissingBean
    DataSourceConnections migrationConnections(ConfigurableListableBeanFactory beans) {
        return new DataSourceConnections(find(beans, MigrationSource.class).dataSource(), find(beans, MigrationTarget.class).dataSource());
    }
    @Bean(destroyMethod = "close") @ConditionalOnMissingBean(MigrationService.class)
    SpringMigrationService migrationService(Environment environment, MigrationConfiguration configuration,
            ConfigurableListableBeanFactory beans, ValueConverter converter, ObjectProvider<MigrationEngine> engines) {
        return new SpringMigrationService(environment, configuration, beans, converter, engines);
    }
    private record Endpoint(DataSource dataSource, int capacity) { }
    private static Endpoint find(ConfigurableListableBeanFactory beans, Class<? extends Annotation> role) {
        List<String> matches = new ArrayList<>();
        for (String name : beans.getBeanNamesForType(DataSource.class, true, false))
            if (beans.findAnnotationOnBean(name, role) != null) matches.add(name);
        if (matches.size() != 1) throw new IllegalArgumentException("Require exactly one @" + role.getSimpleName() + " DataSource");
        String name = matches.get(0);
        int capacity = role == MigrationSource.class ? beans.findAnnotationOnBean(name, MigrationSource.class).capacity()
                : beans.findAnnotationOnBean(name, MigrationTarget.class).capacity();
        if (capacity < 1) throw new IllegalArgumentException("Migration capacity must be positive");
        return new Endpoint(beans.getBean(name, DataSource.class), capacity);
    }
    public static final class SpringMigrationService implements MigrationService, AutoCloseable {
        private final Environment environment;
        private final MigrationConfiguration configuration;
        private final ConfigurableListableBeanFactory beans;
        private final ValueConverter converter;
        private final ObjectProvider<MigrationEngine> engines;
        private MigrationRunner runner;
        private boolean closed;
        SpringMigrationService(Environment environment, MigrationConfiguration configuration,
                ConfigurableListableBeanFactory beans, ValueConverter converter, ObjectProvider<MigrationEngine> engines) {
            this.environment=environment; this.configuration=configuration; this.beans=beans; this.converter=converter; this.engines=engines;
        }
        public MigrationResult migrate() {
            synchronized (this) {
                if (closed) throw new IllegalStateException("Migration service is closed");
            }
            configuration.get();
            MigrationEngine custom = engines.getIfAvailable();
            if (custom != null) return custom.execute(configuration.get().toPlan());
            MigrationRunner current;
            synchronized (this) {
                if (closed) throw new IllegalStateException("Migration service is closed");
                if (runner == null) {
                    var source=find(beans, MigrationSource.class); var target=find(beans, MigrationTarget.class);
                    runner=MigrationRunner.builder().source(source.dataSource(), source.capacity())
                        .target(target.dataSource(), target.capacity()).environment(environment).converter(converter).build();
                }
                current=runner;
            }
            return current.migrate();
        }
        public void close() {
            MigrationRunner current;
            synchronized (this) { closed=true; current=runner; }
            if (current != null) current.close();
        }
    }
}
