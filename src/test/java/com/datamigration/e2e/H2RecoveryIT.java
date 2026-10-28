package com.datamigration.e2e;

import com.datamigration.api.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.execution.*;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class H2RecoveryIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realServerDisconnectionAfterACommittedBatchCanResume(boolean stopTarget) throws Exception {
        try (var databases = new TcpDatabases(directory, false)) {
            databases.seed(10_000);
            AtomicBoolean triggered = new AtomicBoolean();
            ConnectionAccess access = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException {
                    return DatabaseFixture.connection(databases.sourceUrl());
                }
                public Connection openTargetConnection() throws SQLException {
                    Connection delegate = DatabaseFixture.connection(databases.targetUrl());
                    AtomicBoolean business = new AtomicBoolean();
                    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            Object value;
                            try { value = method.invoke(delegate, args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                    && sql.startsWith("INSERT INTO CUSTOMER")) {
                                PreparedStatement prepared = (PreparedStatement) value;
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (p, operation, parameters) -> {
                                        Object outcome;
                                        try { outcome = operation.invoke(prepared, parameters); }
                                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                                        if (operation.getName().equals("executeBatch") || operation.getName().equals("executeUpdate"))
                                            business.set(true);
                                        return outcome;
                                    });
                            }
                            if (method.getName().equals("commit") && business.get() && triggered.compareAndSet(false, true)) {
                                if (stopTarget) databases.stopTarget(); else databases.stopSource();
                                if (stopTarget) throw new SQLNonTransientConnectionException("Lost commit response", "08006");
                            }
                            return value;
                        });
                }
            };
            try (var context = new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties())
                    .initializers(application -> application.getBeanFactory().registerSingleton("faultEngine",
                        new JdbcMigrationEngine(access, 6, 6, databases.sourceUrl()+"|"+databases.targetUrl(),
                                new JdbcValueConverter())))
                    .run("--spring.config.location=classpath:recovery.yml")) {
                assertThatThrownBy(() -> context.getBean(MigrationService.class).migrate()).isInstanceOf(MigrationException.class);
                assertThat(triggered).isTrue();
            }
            if (stopTarget) databases.restartTarget(); else databases.restartSource();
            try (var target = DatabaseFixture.connection(databases.targetUrl());
                 var statement = target.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
                rows.next();
                assertThat(rows.getLong(1)).isGreaterThan(0).isLessThan(10_000);
            }
            // New engine and Spring context: only the durable database ledger carries progress.
            ConnectionAccess restored = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return DatabaseFixture.connection(databases.sourceUrl()); }
                public Connection openTargetConnection() throws SQLException { return DatabaseFixture.connection(databases.targetUrl()); }
            };
            try (var context = new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties())
                    .initializers(application -> application.getBeanFactory().registerSingleton("restoredEngine",
                        new JdbcMigrationEngine(restored, 6, 6, databases.sourceUrl()+"|"+databases.targetUrl(),
                                new JdbcValueConverter())))
                    .run("--spring.config.location=classpath:recovery.yml")) {
                assertThat(context.getBean(MigrationService.class).migrate().tables().get(0).committedRows()).isEqualTo(10_000);
            }
            H2MigrationIT.assertEquivalent(databases);
        }
    }
}
