package com.datamigration.e2e;

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

/** Local synthetic verification only. HostMigrationE2EIT never creates H2 or real database objects. */
class ManualHostRecoveryIT {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest @ValueSource(strings = {"source", "target"})
    void manualCutFailsAndExplicitRunResumesWithTheSameRunner(String side) throws Exception {
        try (var db = new TcpDatabases(directory, false)) {
            db.seed(2000);
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
                     migration.migrate();
                 })) {
                try {
                    post(console, "run");
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
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
        assertThat(response.statusCode()).as(action).isEqualTo(200);
    }

    private void awaitState(ManualMigrationConsole console, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            var response = client.send(HttpRequest.newBuilder(URI.create(console.url() + "status"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("migration=" + state)) return;
            if (state.equals("SUCCEEDED") && response.body().contains("migration=FAILED"))
                throw new AssertionError("Recovery failed: " + response.body());
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + state + ": " + response.body());
            Thread.sleep(20);
        }
    }

    private HikariDataSource pool(String url) {
        var pool = new HikariDataSource(); pool.setJdbcUrl(url); pool.setUsername("sa"); pool.setPassword("");
        pool.setMaximumPoolSize(4); pool.setMinimumIdle(0); pool.setConnectionTimeout(500);
        return pool;
    }
}
