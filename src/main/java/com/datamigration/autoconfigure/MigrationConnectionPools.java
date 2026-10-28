package com.datamigration.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import com.datamigration.properties.MigrationProperties.Endpoint;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns two lazy pools; intentionally NOT a DataSource bean.
 * Construction never loads a driver or opens a connection.
 * Do not close while an engine is executing; shutdown must stop workers first.
 */
public final class MigrationConnectionPools implements AutoCloseable, com.datamigration.jdbc.ConnectionAccess {
    private final Endpoint source;
    private final Endpoint target;
    private HikariDataSource sourcePool;
    private HikariDataSource targetPool;
    private boolean closed;

    public MigrationConnectionPools(Endpoint source, Endpoint target) {
        this.source = source;
        this.target = target;
    }

    public Connection openSourceConnection() throws SQLException {
        return sourcePool().getConnection();
    }

    public Connection openTargetConnection() throws SQLException {
        return targetPool().getConnection();
    }

    // Do not hold the lifecycle lock while waiting for a pooled connection.
    private synchronized HikariDataSource sourcePool() {
        checkOpen();
        if (sourcePool == null) sourcePool = create(source, "migration-source");
        return sourcePool;
    }

    private synchronized HikariDataSource targetPool() {
        checkOpen();
        if (targetPool == null) targetPool = create(target, "migration-target");
        return targetPool;
    }

    public synchronized boolean isInitialized() { return sourcePool != null || targetPool != null; }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Migration connection pools are closed");
    }

    private static HikariDataSource create(Endpoint endpoint, String name) {
        HikariDataSource pool = new HikariDataSource();
        try {
            pool.setPoolName(name);
            pool.setJdbcUrl(endpoint.jdbcUrl());
            pool.setUsername(endpoint.username());
            pool.setPassword(endpoint.password());
            pool.setDriverClassName(endpoint.driverClassName());
            pool.setMaximumPoolSize(endpoint.pool().maximumPoolSize());
            pool.setMinimumIdle(endpoint.pool().minimumIdle());
            pool.setConnectionTimeout(endpoint.pool().connectionTimeout().toMillis());
            pool.setIdleTimeout(endpoint.pool().idleTimeout().toMillis());
            pool.setMaxLifetime(endpoint.pool().maxLifetime().toMillis());
            pool.setAutoCommit(false);
            pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            return pool;
        } catch (RuntimeException exception) {
            pool.close();
            throw exception;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        try {
            if (sourcePool != null) sourcePool.close();
        } finally {
            if (targetPool != null) targetPool.close();
        }
    }
}
