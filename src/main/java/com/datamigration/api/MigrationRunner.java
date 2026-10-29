package com.datamigration.api;

import com.datamigration.conversion.*;
import com.datamigration.execution.JdbcMigrationEngine;
import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.properties.MigrationConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;
import javax.sql.DataSource;

import java.util.*;

/** A synchronous migration session. Creates no Spring context and never closes host DataSources. */
public final class MigrationRunner implements MigrationService, AutoCloseable {
    private final MigrationConfiguration configuration;
    private final DataSourceConnections connections;
    private final int sourceCapacity, targetCapacity;
    private final ValueConverter converter;
    private JdbcMigrationEngine engine;
    private boolean running, closed;

    private MigrationRunner(Builder builder, Environment environment) {
        connections = new DataSourceConnections(builder.source, builder.target);
        if (builder.sourceCapacity < 1 || builder.targetCapacity < 1)
            throw new IllegalArgumentException("Declare positive source and target migration capacity");
        sourceCapacity = builder.sourceCapacity; targetCapacity = builder.targetCapacity;
        converter = Objects.requireNonNull(builder.converter);
        configuration = new MigrationConfiguration(environment);
    }

    public static Builder builder() { return new Builder(); }

    @Override public MigrationResult migrate() {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Migration runner is closed");
            if (running) throw new IllegalStateException("A migration is already running");
            running = true;
        }
        try {
            var plan = configuration.get().toPlan();
            synchronized (this) {
                if (closed) throw new IllegalStateException("Migration runner is closed");
                if (engine == null) {
                    // Validate budgets before opening any connection.
                    var candidate = new JdbcMigrationEngine(connections, sourceCapacity, targetCapacity, converter);
                    candidate.validateBudget(plan);
                    engine = candidate;
                }
            }
            return engine.execute(plan);
        }
        finally { synchronized (this) { running = false; notifyAll(); } }
    }

    @Override public void close() {
        JdbcMigrationEngine current;
        synchronized (this) { closed = true; current = engine; }
        if (current != null) current.close();
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
            return new MigrationRunner(this, configured);
        }
    }
}
