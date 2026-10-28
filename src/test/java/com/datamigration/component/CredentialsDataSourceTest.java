package com.datamigration.autoconfigure;

import com.datamigration.api.*;
import com.datamigration.api.DatabaseCredentialsProvider.EndpointRole;
import com.datamigration.properties.MigrationProperties.*;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CredentialsDataSourceTest {
    private Endpoint endpoint(String provider) {
        return new Endpoint("jdbc:h2:mem:" + UUID.randomUUID(), "sa", null, "org.h2.Driver",
                new Pool(2, 0, Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO), provider);
    }

    @Test void physicalConnectionsRefreshAndFailuresAreRedactedAndRecoverable() throws Exception {
        var endpoint = endpoint("rotating");
        var calls = new AtomicInteger();
        var ds = new CredentialsDataSource(endpoint, EndpointRole.SOURCE, (role, e) -> {
            assertThat(role).isEqualTo(EndpointRole.SOURCE);
            assertThat(e).isSameAs(endpoint);
            if (calls.incrementAndGet() == 2) throw new SQLException("secret-token");
            return new DatabaseCredentials("sa", "");
        });
        try (var c = ds.getConnection()) { assertThat(c.isValid(1)).isTrue(); }
        assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class)
                .hasMessageContaining("SOURCE").hasMessageContaining("rotating")
                .hasMessageNotContaining("secret-token").hasNoCause();
        try (var c = ds.getConnection()) { assertThat(c.isValid(1)).isTrue(); }
        assertThat(calls).hasValue(3);
    }

    @Test void invalidCredentialsAreRejected() {
        for (var credentials : new DatabaseCredentials[]{null, new DatabaseCredentials("other", "secret"),
                new DatabaseCredentials("sa", null)}) {
            var ds = new CredentialsDataSource(endpoint("invalid"), EndpointRole.TARGET, (r, e) -> credentials);
            assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class).hasNoCause();
        }
        assertThat(new DatabaseCredentials("user", "secret").toString()).isEqualTo("DatabaseCredentials[redacted]");
    }

    @Test void poolReusesConnectionsAndKeepsEndpointsSeparateUnderConcurrency() throws Exception {
        var source = endpoint("sourceProvider");
        var target = endpoint("targetProvider");
        var sourceCalls = new AtomicInteger();
        var targetCalls = new AtomicInteger();
        try (var pools = new MigrationConnectionPools(source, target, name -> (role, e) -> {
            assertThat(name).isEqualTo(e.credentialsProvider());
            assertThat(e).isSameAs(role == EndpointRole.SOURCE ? source : target);
            (role == EndpointRole.SOURCE ? sourceCalls : targetCalls).incrementAndGet();
            return new DatabaseCredentials("sa", "");
        })) {
            assertThat(sourceCalls).hasValue(0);
            try (var c = pools.openSourceConnection()) { assertThat(c.getAutoCommit()).isFalse(); }
            int before = sourceCalls.get();
            try (var c = pools.openSourceConnection()) { assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED); }
            assertThat(sourceCalls).hasValue(before);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var a = executor.submit(() -> { try (var c = pools.openSourceConnection()) { return c.getMetaData().getURL(); } });
                var b = executor.submit(() -> { try (var c = pools.openTargetConnection()) { return c.getMetaData().getURL(); } });
                assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(source.jdbcUrl());
                assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(target.jdbcUrl());
            } finally { executor.shutdownNow(); }
            // Evict the physical connection; next borrow must resolve again.
            var broken = pools.openSourceConnection();
            broken.unwrap(org.h2.jdbc.JdbcConnection.class).close();
            assertThatThrownBy(broken::close).isInstanceOf(SQLException.class);
            Thread.sleep(600); // exceeds Hikari's connection-alive bypass window
            try (var c = pools.openSourceConnection()) { assertThat(c.isValid(1)).isTrue(); }
            assertThat(sourceCalls.get()).isGreaterThan(before);
            assertThat(targetCalls.get()).isPositive();
        }
    }

    @Test void missingRegistryAndUnsupportedDataSourceOperationsFailClearly() throws Exception {
        var endpoint = endpoint("missing");
        try (var pools = new MigrationConnectionPools(endpoint, endpoint)) {
            assertThatThrownBy(pools::openSourceConnection).hasMessageContaining("missing").hasMessageContaining("SOURCE");
        }
        var ds = new CredentialsDataSource(endpoint, EndpointRole.SOURCE, (r, e) -> new DatabaseCredentials("sa", ""));
        assertThat(ds.getLogWriter()).isNull();
        assertThat(ds.getLoginTimeout()).isZero();
        assertThat(ds.isWrapperFor(null)).isFalse();
        assertThat(ds.unwrap(javax.sql.DataSource.class)).isSameAs(ds);
        assertThatThrownBy(() -> ds.unwrap(String.class)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> ds.getConnection("sa", "")).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> ds.setLoginTimeout(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> ds.setLogWriter(null)).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(ds::getParentLogger).isInstanceOf(SQLFeatureNotSupportedException.class);
    }
}
