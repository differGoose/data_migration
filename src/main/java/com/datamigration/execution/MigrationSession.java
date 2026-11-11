package com.datamigration.execution;

import com.datamigration.api.MigrationException;
import com.datamigration.api.MigrationResult;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.jdbc.PoolCapacity;
import javax.sql.DataSource;
import java.util.Objects;
import com.datamigration.properties.MigrationConfiguration;

/** Shared synchronous core used only by the public adapters' background caller. */
public final class MigrationSession implements AutoCloseable {
    private final MigrationConfiguration configuration;
    private final ConnectionAccess connections;
    private final Integer sourceCapacity, targetCapacity;
    private final DataSource source, target;
    private int checkedSource, checkedTarget;
    private final ValueConverter converter;
    private JdbcMigrationEngine engine;
    private boolean closed;
    public MigrationSession(MigrationConfiguration configuration, DataSource source, Integer sourceCapacity,
            DataSource target, Integer targetCapacity, ValueConverter converter) {
        MigrationLog.stage("DATASOURCE_RESOLVE", "-", "-");
        this.configuration = configuration; this.converter = converter;
        this.source = Objects.requireNonNull(source, "source DataSource");
        this.target = Objects.requireNonNull(target, "target DataSource");
        this.connections = new DataSourceConnections(source, target);
        MigrationLog.info("Host DataSources supplied", "");
        MigrationLog.stage("CAPACITY_CHECK", "-", "data-migration.strategy.max-concurrent-tables");
        if ((sourceCapacity != null && sourceCapacity < 1) || (targetCapacity != null && targetCapacity < 1))
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
                var budget = ConnectionBudget.forPlan(plan);
                MigrationLog.stage("CAPACITY_CHECK", "-", "data-migration.strategy.max-concurrent-tables");
                if ((sourceCapacity != null && sourceCapacity < budget.source())
                        || (targetCapacity != null && targetCapacity < budget.target())) {
                    MigrationLog.info("Migration connection budget", "requiredSource=" + budget.source()
                            + " declaredSource=" + sourceCapacity + " requiredTarget=" + budget.target() + " declaredTarget=" + targetCapacity);
                    throw new IllegalArgumentException("Insufficient pool capacity; source requires " + budget.source() + ", target requires " + budget.target());
                }
                var sourceLimit = checkPool(source, "source", sourceCapacity, budget.source());
                var targetLimit = checkPool(target, "target", targetCapacity, budget.target());
                if (sourceLimit.pool() != null && sourceLimit.pool() == targetLimit.pool()) {
                    long shared = (long)budget.source() + budget.target();
                    MigrationLog.stage("CAPACITY_CHECK", "shared", "data-migration.strategy.max-concurrent-tables");
                    int sharedMaximum = Math.min(sourceLimit.maximumPoolSize(), targetLimit.maximumPoolSize());
                    if (shared > sharedMaximum)
                        throw new IllegalArgumentException("Insufficient shared pool capacity; required=" + shared
                                + ", maximumPoolSize=" + sharedMaximum);
                }
                synchronized (this) {
                    if (closed) throw new IllegalStateException("Migration runner is closed");
                    if (engine == null || checkedSource != sourceLimit.capacity() || checkedTarget != targetLimit.capacity()) {
                        var candidate = new JdbcMigrationEngine(connections, sourceLimit.capacity(), targetLimit.capacity(), converter);
                        candidate.validateBudget(plan);
                        if (engine != null) engine.close();
                        engine = candidate;
                        checkedSource = sourceLimit.capacity(); checkedTarget = targetLimit.capacity();
                    }
                }
                return engine.execute(plan, monitor);
            } catch (RuntimeException failure) {
                if (!(failure instanceof MigrationException)) MigrationLog.error("Migration invocation failed", failure);
                throw failure;
            }
        }
    }
    private PoolCapacity.Limit checkPool(DataSource dataSource, String endpoint, Integer declared, int required) {
        MigrationLog.stage("CAPACITY_CHECK", endpoint, "data-migration.strategy.max-concurrent-tables");
        var limit = PoolCapacity.resolve(dataSource, endpoint, declared);
        MigrationLog.info("Host pool capacity checked", "required=" + required + " maximumPoolSize="
                + (limit.maximumPoolSize() == null ? "unknown" : limit.maximumPoolSize()) + " migrationCapacity=" + limit.capacity());
        if (limit.capacity() < required)
            throw new IllegalArgumentException("Insufficient " + endpoint + " pool capacity; required=" + required
                    + ", maximumPoolSize=" + limit.maximumPoolSize());
        return limit;
    }
    public synchronized boolean isWorkerThread() { return engine != null && engine.isWorkerThread(); }
    @Override public void close() {
        JdbcMigrationEngine current;
        synchronized (this) { closed = true; current = engine; }
        if (current != null) current.close();
    }
}
