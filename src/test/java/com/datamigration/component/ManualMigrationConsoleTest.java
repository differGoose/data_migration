package com.datamigration.component;

import com.datamigration.support.ManualMigrationConsole;
import com.datamigration.support.TcpFaultProxy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ManualMigrationConsoleTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private HttpResponse<String> request(ManualMigrationConsole console, String method, String action) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(console.url() + action)).timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitState(ManualMigrationConsole console, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!request(console, "GET", "status").body().contains("migration=" + state)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + state);
            Thread.sleep(10);
        }
    }

    @Test void controlsFaultsDuringMigrationAndRequiresExplicitRecoveryAndSuccessfulFinish() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var source = new TcpFaultProxy("127.0.0.1", 1, 0);
             var target = new TcpFaultProxy("127.0.0.1", 1, 0);
             var console = new ManualMigrationConsole(source, target, () -> {
                 if (attempts.incrementAndGet() == 1) {
                     entered.countDown();
                     try { release.await(); }
                     catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                     throw new IllegalStateException("secret-driver-message");
                 }
             })) {
            try {
                assertThat(request(console, "GET", "").body()).contains("Run / Resume");
                assertThat(request(console, "POST", "finish").statusCode()).isEqualTo(409);
                assertThat(request(console, "GET", "source/cut").statusCode()).isEqualTo(405);
                var wrong = URI.create(console.url()).resolve("/status");
                assertThat(client.send(HttpRequest.newBuilder(wrong).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode()).isEqualTo(404);
                assertThat(request(console, "POST", "run").statusCode()).isEqualTo(200);
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(request(console, "POST", "run").statusCode()).isEqualTo(409);
                assertThat(request(console, "POST", "source/cut").statusCode()).isEqualTo(200);
                assertThat(request(console, "POST", "target/cut").statusCode()).isEqualTo(200);
                assertThat(request(console, "GET", "status").body()).contains("source=CUT", "target=CUT");
                release.countDown(); awaitState(console, "FAILED");
                assertThat(request(console, "GET", "status").body()).doesNotContain("secret-driver-message");
                request(console, "POST", "source/restore"); request(console, "POST", "target/restore");
                assertThat(request(console, "GET", "status").body()).contains("source=OPEN", "target=OPEN");
                assertThat(attempts.get()).isEqualTo(1); // Restore never retries the migration automatically.
                assertThat(request(console, "POST", "finish").statusCode()).isEqualTo(409);
                request(console, "POST", "run"); awaitState(console, "SUCCEEDED");
                assertThat(attempts.get()).isEqualTo(2);
                assertThat(request(console, "POST", "finish").statusCode()).isEqualTo(200);
                console.await(Duration.ofSeconds(3));
                assertThat(request(console, "POST", "run").statusCode()).isEqualTo(409);
            } finally { release.countDown(); }
        }
    }

    @Test void closingInterruptsAndWaitsForMigrationCleanup() throws Exception {
        var entered = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
        try (var source = new TcpFaultProxy("127.0.0.1", 1, 0);
             var target = new TcpFaultProxy("127.0.0.1", 1, 0)) {
            var console = new ManualMigrationConsole(source, target, () -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { cleaned.countDown(); }
            });
            try {
                request(console, "POST", "run");
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            } finally { console.close(); }
            assertThat(cleaned.getCount()).isZero();
        }
    }

    @Test void idleSessionCanTimeoutOrAbortWithoutReportingSuccess() throws Exception {
        try (var source = new TcpFaultProxy("127.0.0.1", 1, 0);
             var target = new TcpFaultProxy("127.0.0.1", 1, 0);
             var console = new ManualMigrationConsole(source, target, () -> { })) {
            assertThatThrownBy(() -> console.await(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> console.await(Duration.ofMillis(10))).isInstanceOf(TimeoutException.class);
            assertThat(request(console, "POST", "abort").statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> console.await(Duration.ofSeconds(1))).isInstanceOf(ExecutionException.class);
        }
    }
}
