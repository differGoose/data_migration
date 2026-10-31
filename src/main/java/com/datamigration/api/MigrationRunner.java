package com.datamigration.api;

import com.datamigration.conversion.*;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.execution.AsyncMigration;
import com.datamigration.execution.MigrationSession;
import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.properties.MigrationConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;
import javax.sql.DataSource;

import java.util.*;

/** A migration session with synchronous and asynchronous entry points. Creates no Spring context and never closes host DataSources. */
public final class MigrationRunner implements MigrationService, AutoCloseable {
    private final MigrationSession session;
    private final AsyncMigration asynchronous;

    private MigrationRunner(Builder builder, Environment environment) {
        session = new MigrationSession(new MigrationConfiguration(environment),
                new DataSourceConnections(builder.source, builder.target), builder.sourceCapacity,
                builder.targetCapacity, Objects.requireNonNull(builder.converter));
        asynchronous = new AsyncMigration(session::execute);
    }
    public static Builder builder() { return new Builder(); }
    @Override public MigrationExecution migrate() { return asynchronous.startSync(); }
    @Override public MigrationExecution migrateAsync() { return asynchronous.start(); }
    @Override public void close() {
        session.close();
        asynchronous.close(!session.isWorkerThread());
    }

    public static final class Builder {
        private DataSource source, target;
        private int sourceCapacity, targetCapacity;
        private String[] locations = {}, arguments = {};
        private Environment environment;
        private ValueConverter converter = new JdbcValueConverter();
        private Builder() { }
        public Builder source(DataSource value, int capacity) { source = value; sourceCapacity = capacity; return this; }
        public Builder target(DataSource value, int capacity) { target = value; targetCapacity = capacity; return this; }
        public Builder configLocations(String... values) { locations = values.clone(); return this; }
        public Builder arguments(String... values) { arguments = values.clone(); return this; }
        /** Spring adapter uses its existing Environment; no second configuration loading pass. */
        public Builder environment(Environment value) { environment = Objects.requireNonNull(value); return this; }
        public Builder converter(ValueConverter value) { converter = value; return this; }
        public MigrationRunner build() {
            try (var invocation = MigrationLog.invocation()) {
                MigrationLog.stage("CONFIG_LOAD", "-", "spring.config.location");
                MigrationLog.info("Migration configuration loading started", "locations=" + locations.length
                        + " existingEnvironment=" + (environment != null));
                try { return buildConfigured(); }
                catch (RuntimeException failure) { MigrationLog.error("Migration configuration loading failed", failure); throw failure; }
            }
        }
        private MigrationRunner buildConfigured() {
            Environment configured = environment;
            if (configured == null) {
                var env = new StandardEnvironment();
                if (locations.length > 0) env.getPropertySources().addFirst(new MapPropertySource("migrationLocations",
                        Map.of("spring.config.location", String.join(",", locations))));
                env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
                ConfigDataEnvironmentPostProcessor.applyTo(env);
                configured = env;
            } else if (locations.length > 0 || arguments.length > 0) {
                throw new IllegalArgumentException("Use either environment or configLocations/arguments");
            }
            MigrationLog.info("Migration configuration environment ready", "");
            return new MigrationRunner(this, configured);
        }
    }
}
