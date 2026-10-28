package com.datamigration.autoconfigure;

import com.datamigration.api.DatabaseCredentialsProvider;
import com.datamigration.api.DatabaseCredentialsProvider.EndpointRole;
import com.datamigration.properties.MigrationProperties.Endpoint;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Internal, unpooled adapter; Hikari owns the returned connections. */
final class CredentialsDataSource implements DataSource {
    private final Endpoint endpoint;
    private final EndpointRole role;
    private final DatabaseCredentialsProvider provider;
    private final Driver driver;

    CredentialsDataSource(Endpoint endpoint, EndpointRole role, DatabaseCredentialsProvider provider) {
        this.endpoint = endpoint;
        this.role = role;
        this.provider = provider;
        try {
            driver = (Driver) Class.forName(endpoint.driverClassName(), true,
                    Thread.currentThread().getContextClassLoader()).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot initialize JDBC driver for " + role);
        }
    }

    @Override public Connection getConnection() throws SQLException {
        try {
            var credentials = provider.resolve(role, endpoint);
            if (credentials == null || !endpoint.username().equals(credentials.username()) || credentials.password() == null)
                throw new SQLException();
            var properties = new Properties();
            properties.setProperty("user", credentials.username());
            properties.setProperty("password", credentials.password());
            Connection connection = driver.connect(endpoint.jdbcUrl(), properties);
            if (connection == null) throw new SQLException();
            return connection;
        } catch (SQLException | RuntimeException e) {
            // Provider/driver messages and causes may contain tokens, passwords or URLs.
            throw new SQLException("Cannot establish connection for " + role + " using credentials provider "
                    + endpoint.credentialsProvider());
        }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Explicit credentials are not supported");
    }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public int getLoginTimeout() { return 0; }
    @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return type != null && type.isInstance(this); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (isWrapperFor(type)) return type.cast(this);
        throw new SQLException("Unsupported DataSource wrapper");
    }
}
