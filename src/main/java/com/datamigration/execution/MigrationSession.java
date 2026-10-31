package com.datamigration.execution;

import com.datamigration.api.MigrationException;
import com.datamigration.api.MigrationResult;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.properties.MigrationConfiguration;

/** Shared synchronous core used only by the public adapters' background caller. */
public final class MigrationSession implements AutoCloseable {
    private final MigrationConfiguration configuration;
    private final ConnectionAccess connections;
    private final int sourceCapacity, targetCapacity;
    private final ValueConverter converter;
    private JdbcMigrationEngine engine;
    private boolean closed;
    public MigrationSession(MigrationConfiguration configuration, ConnectionAccess connections,
            int sourceCapacity, int targetCapacity, ValueConverter converter) {
        MigrationLog.stage("DATASOURCE_RESOLVE", "-", "-");
        this.configuration = configuration; this.connections = connections; this.converter = converter;
        MigrationLog.info("Host DataSources supplied", "");
        MigrationLog.stage("CAPACITY_CHECK", "-", "data-migration.strategy.max-concurrent-tables");
        if (sourceCapacity < 1 || targetCapacity < 1)
            throw new IllegalArgumentException("Declare positive source and target migration capacity");
        this.sourceCapacity = sourceCapacity; this.targetCapacity = targetCapacity;
    }
    public MigrationResult execute(MigrationMonitor monitor) {
        try (var invocation = MigrationLog.invocation()) {
            try {
                MigrationLog.stage("CONFIG_BIND", "-", "data-migration");
                var properties = configuration.get();
                MigrationLog.job(properties.jobId());
                for (int i = 0; i < properties.tables().size(); i++)
                    MigrationLog.taskPath(properties.tables().get(i).name(), i);
                var plan = properties.toPlan();
                monitor.plan(plan);
                MigrationLog.info("Migration configuration ready", "tables=" + plan.tables().size()
                        + " maxConcurrentTables=" + plan.strategy().maxConcurrentTables());
                synchronized (this) {
                    if (closed) throw new IllegalStateException("Migration runner is closed");
                    if (engine == null) {
                        MigrationLog.stage("CAPACITY_CHECK", "-", "data-migration.strategy.max-concurrent-tables");
                        var candidate = new JdbcMigrationEngine(connections, sourceCapacity, targetCapacity, converter);
                        candidate.validateBudget(plan);
                        engine = candidate;
                    }
                }
                return engine.execute(plan, monitor);
            } catch (RuntimeException failure) {
                if (!(failure instanceof MigrationException)) MigrationLog.error("Migration invocation failed", failure);
                throw failure;
            }
        }
    }
    public synchronized boolean isWorkerThread() { return engine != null && engine.isWorkerThread(); }
    @Override public void close() {
        JdbcMigrationEngine current;
        synchronized (this) { closed = true; current = engine; }
        if (current != null) current.close();
    }
}
