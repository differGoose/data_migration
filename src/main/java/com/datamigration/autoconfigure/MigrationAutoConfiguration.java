package com.datamigration.autoconfigure;

import com.datamigration.api.*;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.conversion.*;
import com.datamigration.execution.*;
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
    SpringMigrationService migrationService(MigrationConfiguration configuration,
            ConfigurableListableBeanFactory beans, ValueConverter converter, ObjectProvider<MigrationEngine> engines) {
        return new SpringMigrationService(configuration, beans, converter, engines);
    }
    private record Endpoint(DataSource dataSource, int capacity) { }
    private static Endpoint find(ConfigurableListableBeanFactory beans, Class<? extends Annotation> role) {
        try (var diagnostic = MigrationLog.phase("DATASOURCE_RESOLVE", role == MigrationSource.class ? "source" : "target", "-")) {
            try { return findEndpoint(beans, role); }
            catch (RuntimeException failure) { MigrationLog.error("Host DataSource selection failed", failure); throw failure; }
        }
    }
    private static Endpoint findEndpoint(ConfigurableListableBeanFactory beans, Class<? extends Annotation> role) {
        List<String> matches = new ArrayList<>();
        for (String name : beans.getBeanNamesForType(DataSource.class, true, false))
            if (beans.findAnnotationOnBean(name, role) != null) matches.add(name);
        if (matches.size() != 1) {
            MigrationLog.warn("Host DataSource role is missing or ambiguous", "matchingBeans=" + matches.size());
            throw new IllegalArgumentException("Require exactly one @" + role.getSimpleName() + " DataSource");
        }
        String name = matches.get(0);
        int capacity = role == MigrationSource.class ? beans.findAnnotationOnBean(name, MigrationSource.class).capacity()
                : beans.findAnnotationOnBean(name, MigrationTarget.class).capacity();
        if (capacity < 1) throw new IllegalArgumentException("Migration capacity must be positive");
        var dataSource = beans.getBean(name, DataSource.class);
        MigrationLog.info("Host DataSource selected", "bean=" + MigrationLog.safe(name) + " capacity=" + capacity);
        return new Endpoint(dataSource, capacity);
    }
    public static final class SpringMigrationService implements MigrationService, AutoCloseable {
        private final MigrationConfiguration configuration;
        private final ConfigurableListableBeanFactory beans;
        private final ValueConverter converter;
        private final ObjectProvider<MigrationEngine> engines;
        private MigrationSession session;
        private final AsyncMigration asynchronous;
        private boolean closed;
        SpringMigrationService(MigrationConfiguration configuration,
                ConfigurableListableBeanFactory beans, ValueConverter converter, ObjectProvider<MigrationEngine> engines) {
            this.configuration=configuration; this.beans=beans; this.converter=converter; this.engines=engines;
            asynchronous = new AsyncMigration(this::execute);
        }
        public MigrationExecution migrate() { return asynchronous.startSync(); }
        public MigrationExecution migrateAsync() { return asynchronous.start(); }
        private MigrationResult execute(MigrationMonitor monitor) {
            var plan = configuration.get().toPlan();
            MigrationLog.job(plan.jobId());
            monitor.plan(plan);
            MigrationEngine custom = engines.getIfAvailable();
            if (custom != null) return custom.execute(plan);
            MigrationSession current;
            synchronized (this) {
                if (closed) throw new IllegalStateException("Migration service is closed");
                if (session == null) {
                    var source = find(beans, MigrationSource.class);
                    var target = find(beans, MigrationTarget.class);
                    session = new MigrationSession(configuration,
                            new DataSourceConnections(source.dataSource(), target.dataSource()),
                            source.capacity(), target.capacity(), converter);
                }
                current = session;
            }
            return current.execute(monitor);
        }
        public void close() {
            MigrationSession current;
            synchronized (this) { closed = true; current = session; }
            if (current != null) current.close();
            asynchronous.close(current == null || !current.isWorkerThread());
        }
    }
}
