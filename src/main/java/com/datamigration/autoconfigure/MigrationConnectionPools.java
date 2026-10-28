package com.datamigration.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import com.datamigration.properties.MigrationProperties.Endpoint;
import java.sql.Connection;
import java.sql.SQLException;
import com.datamigration.api.DatabaseCredentialsProvider;
import com.datamigration.api.DatabaseCredentialsProvider.EndpointRole;
import java.util.function.Function;

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
    private final Function<String, DatabaseCredentialsProvider> providers;

    public MigrationConnectionPools(Endpoint source, Endpoint target) {
        this(source, target, name -> { throw new IllegalArgumentException("No credentials provider registry: " + name); });
    }

    public MigrationConnectionPools(Endpoint source, Endpoint target,
                                    Function<String, DatabaseCredentialsProvider> providers) {
        this.source = source;
        this.target = target;
        this.providers = java.util.Objects.requireNonNull(providers);
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
        if (sourcePool == null) sourcePool = create(source, "migration-source", EndpointRole.SOURCE);
        return sourcePool;
    }

    private synchronized HikariDataSource targetPool() {
        checkOpen();
        if (targetPool == null) targetPool = create(target, "migration-target", EndpointRole.TARGET);
        return targetPool;
    }

    public synchronized boolean isInitialized() { return sourcePool != null || targetPool != null; }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Migration connection pools are closed");
    }

    private HikariDataSource create(Endpoint endpoint, String name, EndpointRole role) {
        HikariDataSource pool = new HikariDataSource();
        try {
            pool.setPoolName(name);
            if (endpoint.credentialsProvider() == null) {
                pool.setJdbcUrl(endpoint.jdbcUrl());
                pool.setUsername(endpoint.username());
                pool.setPassword(endpoint.password());
                pool.setDriverClassName(endpoint.driverClassName());
            } else {
                DatabaseCredentialsProvider provider;
                try {
                    provider = java.util.Objects.requireNonNull(providers.apply(endpoint.credentialsProvider()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Cannot resolve credentials provider "
                            + endpoint.credentialsProvider() + " for " + role);
                }
                pool.setDataSource(new CredentialsDataSource(endpoint, role, provider));
            }
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
