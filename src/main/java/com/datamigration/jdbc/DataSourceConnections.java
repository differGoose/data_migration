package com.datamigration.jdbc;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
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

    public Connection openSourceConnection() throws SQLException { return borrow(source); }
    public Connection openTargetConnection() throws SQLException { return borrow(target); }

    private Connection borrow(DataSource dataSource) throws SQLException {
        Connection raw;
        try { raw = dataSource.getConnection(); }
        catch (SQLException | RuntimeException failure) { throw sanitized("Cannot borrow migration connection", failure); }
        try {
            boolean autoCommit = raw.getAutoCommit();
            int isolation = raw.getTransactionIsolation();
            raw.setAutoCommit(false);
            raw.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
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
                                } finally { raw.close(); }
                                return null;
                            }
                            try { return method.invoke(raw, args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        }
                    });
        } catch (SQLException | RuntimeException failure) {
            try { raw.close(); } catch (SQLException ignored) { }
            throw sanitized("Cannot initialize migration connection", failure);
        }
    }

    private SQLException sanitized(String message, Exception failure) {
        // Preserve classification for bounded retries, while dropping secret-bearing messages and causes.
        return failure instanceof SQLException sql
                ? new SQLException(message, sql.getSQLState(), sql.getErrorCode()) : new SQLException(message);
    }

    /** Only a digest leaves this method; metadata and credentials must never be logged. */
    public String identity() throws SQLException {
        try (var s = openSourceConnection(); var t = openTargetConnection()) {
            String material = "datasource-v2\n" + endpoint(s) + "\n" + endpoint(t);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (SQLException | RuntimeException | NoSuchAlgorithmException failure) {
            throw new SQLException("Cannot establish stable migration endpoint identity");
        }
    }

    private String endpoint(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        String url = metadata.getURL(), user = metadata.getUserName();
        if (url == null || !url.startsWith("jdbc:") || user == null || user.isBlank())
            throw new SQLException("Missing endpoint identity");
        // Connection options can contain rotating passwords/tokens. Catalog/schema retain namespace identity.
        String base = url.split("[?;]", 2)[0]
                .replaceFirst("(jdbc:oracle:thin:)[^@]*(?=@)", "$1")
                .replaceAll("//[^/@]+:[^/@]*@", "//");
        return base + "\n" + user + "\n" + connection.getCatalog() + "\n" + connection.getSchema();
    }
}
