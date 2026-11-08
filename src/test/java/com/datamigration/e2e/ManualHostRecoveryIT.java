package com.datamigration.e2e;

import static com.datamigration.support.MigrationAwait.await;

import com.datamigration.api.MigrationRunner;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.support.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * Exercises manual source/target cuts and explicit recovery with the same runner.
 * Slow connection initialization reproduces pool startup races on slower test hosts.
 */
class ManualHostRecoveryIT {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest @ValueSource(strings = {"source", "target"})
    void manualCutFailsAndExplicitRunResumesWithTheSameRunner(String side) throws Exception {
        try (var db = new TcpDatabases(directory)) {
            db.seed(2000);
            DatabaseFixture.execute(db.sourceUrl(), "CREATE ALIAS TEST_CONNECTION_DELAY FOR 'java.lang.Thread.sleep'");
            DatabaseFixture.execute(db.targetUrl(), "CREATE ALIAS TEST_CONNECTION_DELAY FOR 'java.lang.Thread.sleep'");
            int sourcePort = URI.create(db.sourceUrl().substring("jdbc:h2:".length())).getPort();
            int targetPort = URI.create(db.targetUrl().substring("jdbc:h2:".length())).getPort();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var pause = new AtomicBoolean(true);
            try (var sourceProxy = new TcpFaultProxy("127.0.0.1", sourcePort, 0);
                 var targetProxy = new TcpFaultProxy("127.0.0.1", targetPort, 0);
                 var source = pool(db.sourceUrl().replace(":" + sourcePort + "/", ":" + sourceProxy.port() + "/"));
                 var target = pool(db.targetUrl().replace(":" + targetPort + "/", ":" + targetProxy.port() + "/"));
                 var migration = MigrationRunner.builder().source(source, 4).target(target, 4)
                         .configLocations("classpath:e2e.yml")
                         .arguments("--data-migration.job-id=manual", "--data-migration.strategy.transient-retry-count=0")
                         .converter((value, type) -> {
                             if (pause.compareAndSet(true, false)) {
                                 entered.countDown();
                                 try { release.await(); }
                                 catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.sql.SQLException("cancelled"); }
                             }
                             return new JdbcValueConverter().convert(value, type);
                         }).build();
                 var console = new ManualMigrationConsole(sourceProxy, targetProxy, () -> {
                     if (source.getHikariPoolMXBean() != null) source.getHikariPoolMXBean().softEvictConnections();
                     if (target.getHikariPoolMXBean() != null) target.getHikariPoolMXBean().softEvictConnections();
                     await(migration);
                 })) {
                try {
                    post(console, "run");
                    boolean paused = entered.await(30, TimeUnit.SECONDS);
                    assertThat(paused).as("Migration did not reach the fault boundary: %s", status(console)).isTrue();
                    post(console, side + "/cut"); release.countDown();
                    awaitState(console, "FAILED");
                    post(console, side + "/restore");
                    post(console, "run"); awaitState(console, "SUCCEEDED");
                    post(console, "run"); awaitState(console, "SUCCEEDED");
                    try (var connection = DatabaseFixture.connection(db.targetUrl());
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
                        rows.next(); assertThat(rows.getLong(1)).isEqualTo(2000);
                    }
                    assertThat(sourceProxy.forwardedConnections()).isPositive();
                    assertThat(targetProxy.forwardedConnections()).isPositive();
                    post(console, "finish"); console.await(Duration.ofSeconds(3));
                } finally { release.countDown(); }
            }
        }
    }

    private void post(ManualMigrationConsole console, String action) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(console.url() + action))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Action %s: %s", action, response.body()).isEqualTo(200);
    }

    private String status(ManualMigrationConsole console) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(console.url() + "status"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("status").isEqualTo(200);
        return response.body();
    }

    private void awaitState(ManualMigrationConsole console, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            String current = status(console);
            if (current.contains("migration=" + state)) return;
            if (state.equals("SUCCEEDED") && current.contains("migration=FAILED"))
                throw new AssertionError("Recovery failed: " + current);
            if (state.equals("FAILED") && current.contains("migration=SUCCEEDED"))
                throw new AssertionError("Fault did not fail the migration: " + current);
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + state + ": " + current);
            Thread.sleep(20);
        }
    }

    private HikariDataSource pool(String url) {
        var pool = new HikariDataSource(); pool.setJdbcUrl(url); pool.setUsername("sa"); pool.setPassword("");
        pool.setMaximumPoolSize(4); pool.setMinimumIdle(0);
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        // Keep startup slower than the former 500 ms acquisition timeout to catch this regression.
        pool.setConnectionInitSql("CALL TEST_CONNECTION_DELAY(750)");
        return pool;
    }
}
