package com.datamigration.jdbc;

import java.lang.reflect.*;
import com.datamigration.diagnostics.MigrationLog;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Borrows connections only. The host retains ownership of both DataSources. */
public final class DataSourceConnections implements ConnectionAccess {
    private final DataSource source;
    private final DataSource target;

    public DataSourceConnections(DataSource source, DataSource target) {
        this.source = Objects.requireNonNull(source, "source DataSource");
        this.target = Objects.requireNonNull(target, "target DataSource");
    }

    public Connection openSourceConnection() throws SQLException { return borrow(source, "source"); }
    public Connection openTargetConnection() throws SQLException { return borrow(target, "target"); }

    private Connection borrow(DataSource dataSource, String endpoint) throws SQLException {
        try (var diagnostic = MigrationLog.phase("CONNECTION_ACQUIRE", endpoint, "-")) {
            MigrationLog.connecting(endpoint);
            MigrationLog.debug("Borrowing host connection", "");
            try { return initialize(dataSource, endpoint); }
            catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
        }
    }
    private Connection initialize(DataSource dataSource, String endpoint) throws SQLException {
        Connection raw;
        try { raw = dataSource.getConnection(); }
        catch (SQLException | RuntimeException failure) {
            MigrationLog.remember(failure);
            throw sanitized("Cannot borrow migration connection", failure);
        }
        MigrationLog.stage("CONNECTION_INITIALIZE", endpoint, "-");
        try {
            boolean autoCommit = raw.getAutoCommit();
            int isolation = raw.getTransactionIsolation();
            raw.setAutoCommit(false);
            raw.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            MigrationLog.connected(endpoint);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    new InvocationHandler() {
                        private boolean closed;
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getName().equals("close")) {
                                if (closed) return null;
                                closed = true;
                                try {
                                    // Never restore autoCommit=true unless rollback succeeded: it could commit pending writes.
                                    raw.rollback();
                                    raw.setTransactionIsolation(isolation);
                                    raw.setAutoCommit(autoCommit);
                                } catch (SQLException | RuntimeException failure) {
                                    try (var cleanup = MigrationLog.phase("CONNECTION_CLEANUP", endpoint, "-")) {
                                        MigrationLog.remember(failure);
                                        MigrationLog.secondary("Host connection transaction cleanup failed", failure);
                                    }
                                    throw failure;
                                } finally {
                                    try { raw.close(); }
                                    catch (SQLException failure) {
                                        try (var cleanup = MigrationLog.phase("CONNECTION_CLEANUP", endpoint, "-")) {
                                            MigrationLog.remember(failure);
                                            MigrationLog.secondary("Host connection return failed", failure);
                                        }
                                        throw failure;
                                    }
                                }
                                MigrationLog.debug("Host connection returned", "endpoint=" + endpoint);
                                return null;
                            }
                            try { return method.invoke(raw, args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        }
                    });
        } catch (SQLException | RuntimeException failure) {
            MigrationLog.remember(failure);
            try { raw.close(); } catch (SQLException ignored) { }
            throw sanitized("Cannot initialize migration connection", failure);
        }
    }

    private SQLException sanitized(String message, Exception failure) {
        // Preserve classification for bounded retries, while dropping secret-bearing messages and causes.
        return failure instanceof SQLException sql
                ? new SQLException(message, sql.getSQLState(), sql.getErrorCode()) : new SQLException(message);
    }

}
