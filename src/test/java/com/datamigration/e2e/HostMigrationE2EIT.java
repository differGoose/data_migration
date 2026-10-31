package com.datamigration.e2e;

import static com.datamigration.support.MigrationAwait.await;

import com.datamigration.api.TableResult;
import com.datamigration.api.MigrationRunner;
import com.datamigration.support.ManualMigrationConsole;
import com.datamigration.support.TcpFaultProxy;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Simulates a host supplying real DataSources. No H2, schema creation or data preparation. */
@EnabledIfSystemProperty(named = "host.e2e.enabled", matches = "true")
class HostMigrationE2EIT {
    // Fill these in locally. Never commit real addresses, usernames or passwords.
    private static final String SOURCE_JDBC_URL = "";
    private static final String SOURCE_USERNAME = "";
    private static final String SOURCE_PASSWORD = "";

    private static final String TARGET_JDBC_URL = "";
    private static final String TARGET_USERNAME = "";
    private static final String TARGET_PASSWORD = "";

    // Use the real migration configuration in src/test/resources/application.yml.
    // Only data-migration is required; no e2e configuration or validation SQL.
    private static final String CONFIG_LOCATION = "classpath:application.yml";
    private static final int SOURCE_CAPACITY = 4;
    private static final int TARGET_CAPACITY = 4;

    // Used only with -Dhost.e2e.manual=true. These are the REAL upstream endpoints.
    private static final String SOURCE_UPSTREAM_HOST = "";
    private static final int SOURCE_UPSTREAM_PORT = 1521;
    private static final String TARGET_UPSTREAM_HOST = "";
    private static final int TARGET_UPSTREAM_PORT = 5432;
    // In manual mode JDBC URLs must point to the local proxy, for example:
    // Oracle: jdbc:oracle:thin:@//127.0.0.1:{proxy-port}/YOUR_SERVICE
    // PostgreSQL: jdbc:postgresql://127.0.0.1:{proxy-port}/YOUR_DATABASE
    // Preserve required TLS identity/driver settings in your actual URL.

    @Test
    void hostSuppliesRealDataSourcesAndCallsMigration() throws Exception {
        requireConnectionSettings();
        if (Boolean.getBoolean("host.e2e.manual")) {
            runWithManualProxies();
            return;
        }

        // HOST owns pool creation and closing. Replace these factories with your host's own code.
        try (var source = createPool("host-e2e-source", SOURCE_JDBC_URL, SOURCE_USERNAME,
                     SOURCE_PASSWORD, SOURCE_CAPACITY);
             var target = createPool("host-e2e-target", TARGET_JDBC_URL, TARGET_USERNAME,
                     TARGET_PASSWORD, TARGET_CAPACITY);
             var migration = MigrationRunner.builder()
                     .source(source, SOURCE_CAPACITY)
                     .target(target, TARGET_CAPACITY)
                     .configLocations(CONFIG_LOCATION)
                     .build()) {

            // Exactly the call a real host makes. Failure propagates and fails this test.
            var result = await(migration);

            assertThat(result.tables()).allSatisfy(table ->
                    assertThat(table.status()).isEqualTo(TableResult.Status.SUCCEEDED));
            assertThat(source.isClosed()).isFalse();
            assertThat(target.isClosed()).isFalse();
        }
    }

    private static void runWithManualProxies() throws Exception {
        if (SOURCE_UPSTREAM_HOST.isBlank() || TARGET_UPSTREAM_HOST.isBlank())
            throw new IllegalArgumentException("Fill in both real proxy upstream hosts");
        if (!SOURCE_JDBC_URL.contains("{proxy-port}") || !TARGET_JDBC_URL.contains("{proxy-port}"))
            throw new IllegalArgumentException("Manual JDBC URLs must route through local {proxy-port} placeholders");

        try (var sourceProxy = new TcpFaultProxy(SOURCE_UPSTREAM_HOST, SOURCE_UPSTREAM_PORT, 0);
             var targetProxy = new TcpFaultProxy(TARGET_UPSTREAM_HOST, TARGET_UPSTREAM_PORT, 0);
             var source = createPool("host-e2e-source", proxyUrl(SOURCE_JDBC_URL, sourceProxy),
                     SOURCE_USERNAME, SOURCE_PASSWORD, SOURCE_CAPACITY);
             var target = createPool("host-e2e-target", proxyUrl(TARGET_JDBC_URL, targetProxy),
                     TARGET_USERNAME, TARGET_PASSWORD, TARGET_CAPACITY);
             var migration = MigrationRunner.builder()
                     .source(source, SOURCE_CAPACITY).target(target, TARGET_CAPACITY)
                     .configLocations(CONFIG_LOCATION).build();
             var console = new ManualMigrationConsole(sourceProxy, targetProxy, () -> {
                 // Explicit host reconnection: discard sessions broken by the previous manual cut.
                 evictConnections(source);
                 evictConnections(target);
                 await(migration);
                 assertThat(sourceProxy.forwardedConnections()).as("Source traffic must pass through proxy").isPositive();
                 assertThat(targetProxy.forwardedConnections()).as("Target traffic must pass through proxy").isPositive();
             })) {
            System.out.println("Manual migration control: " + console.url());
            System.out.println("Open this URL, then click Run. Keep the original job-id and configuration for recovery.");
            console.await(java.time.Duration.ofSeconds(Long.getLong("host.e2e.manual-timeout-seconds", 1800L)));
        }
    }

    private static String proxyUrl(String template, TcpFaultProxy proxy) {
        return template.replace("{proxy-port}", Integer.toString(proxy.port()));
    }

    private static void evictConnections(HikariDataSource pool) {
        var management = pool.getHikariPoolMXBean();
        if (management != null) management.softEvictConnections();
    }

    private static void requireConnectionSettings() {
        if (SOURCE_JDBC_URL.isBlank() || SOURCE_USERNAME.isBlank()
                || TARGET_JDBC_URL.isBlank() || TARGET_USERNAME.isBlank()) {
            throw new IllegalArgumentException("Fill in both real JDBC URLs and usernames in HostMigrationE2EIT");
        }
        // Empty passwords are allowed when supported by the supplied database/driver.
    }

    private static HikariDataSource createPool(String name, String url, String username,
                                               String password, int capacity) {
        var pool = new HikariDataSource();
        pool.setPoolName(name);
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(capacity);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10_000);
        return pool;
    }
}
