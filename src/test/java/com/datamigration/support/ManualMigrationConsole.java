package com.datamigration.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.datamigration.execution.ExecutorScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

/** Local, explicitly enabled test control. Never logs JDBC settings or driver exception messages. */
public final class ManualMigrationConsole implements AutoCloseable {
    private final TcpFaultProxy source;
    private final TcpFaultProxy target;
    private final Runnable migrate;
    private final HttpServer server;
    private final ExecutorService http = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "manual-migration-http"); thread.setDaemon(true); return thread;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final String path = "/" + UUID.randomUUID() + "/";
    private String state = "READY";
    private String failureType = "NONE";
    private boolean sourceCut;
    private boolean targetCut;
    private boolean running;
    private boolean closed;
    private boolean successful;

    public ManualMigrationConsole(TcpFaultProxy source, TcpFaultProxy target, Runnable migrate) throws IOException {
        this.source = source; this.target = target; this.migrate = migrate;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(http);
        server.start();
    }

    /** Unguessable local capability URL; do not expose the server on a non-loopback interface. */
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    public void await(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive manual session timeout required");
        finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handle(HttpExchange request) throws IOException {
        try {
            String route = request.getRequestURI().getPath();
            // All routes require the random capability, including read-only status.
            if (!route.startsWith(path)) { respond(request, 404, "Not found", false); return; }
            String action = route.substring(path.length());
            if (request.getRequestMethod().equals("GET") && action.isEmpty()) {
                respond(request, 200, page(), true); return;
            }
            if (request.getRequestMethod().equals("GET") && action.equals("status")) {
                respond(request, 200, status(), false); return;
            }
            if (!request.getRequestMethod().equals("POST")) { respond(request, 405, "Use POST", false); return; }
            synchronized (this) {
                if (closed || finished.isDone()) { respond(request, 409, "Session ended", false); return; }
                switch (action) {
                    case "source/cut" -> { source.cut(); sourceCut = true; }
                    case "source/restore" -> { source.restore(); sourceCut = false; }
                    case "target/cut" -> { target.cut(); targetCut = true; }
                    case "target/restore" -> { target.restore(); targetCut = false; }
                    case "run" -> {
                        if (running) { respond(request, 409, "Migration is still running", false); return; }
                        running = true; successful = false; state = "RUNNING"; failureType = "NONE";
                        worker.submit(() -> {
                            boolean success = false;
                            try { migrate.run(); success = true; }
                            catch (Throwable failure) {
                                synchronized (ManualMigrationConsole.this) {
                                    failureType = com.datamigration.execution.SqlErrors.summary(failure);
                                    if (failure instanceof com.datamigration.api.MigrationException migrationFailure) {
                                        failureType = migrationFailure.result().tables().stream()
                                                .filter(table -> table.status() != com.datamigration.api.TableResult.Status.SUCCEEDED)
                                                .map(table -> table.status() + ":" + table.sanitizedError())
                                                .collect(java.util.stream.Collectors.joining(","));
                                    }
                                }
                            }
                            finally {
                                synchronized (ManualMigrationConsole.this) {
                                    successful = success; running = false;
                                    state = success ? "SUCCEEDED" : "FAILED";
                                }
                            }
                        });
                    }
                    case "finish" -> {
                        if (running || !successful) { respond(request, 409, "Finish requires a successful completed migration", false); return; }
                        respond(request, 200, "Session finished successfully", false);
                        request.close(); finished.complete(null); return;
                    }
                    case "abort" -> {
                        respond(request, 200, "Session aborted", false);
                        request.close();
                        finished.completeExceptionally(new IllegalStateException("Manual migration session aborted"));
                        return;
                    }
                    default -> { respond(request, 404, "Unknown action", false); return; }
                }
            }
            respond(request, 200, "OK", false);
        } finally { request.close(); }
    }

    private synchronized String status() {
        return "migration=" + state + "\nfailure=" + failureType + "\nsource=" + (sourceCut ? "CUT" : "OPEN")
                + "\ntarget=" + (targetCut ? "CUT" : "OPEN")
                + "\nsourceForwarded=" + source.forwardedConnections()
                + "\ntargetForwarded=" + target.forwardedConnections();
    }

    private static void respond(HttpExchange request, int code, String body, boolean html) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", (html ? "text/html" : "text/plain") + "; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.getResponseHeaders().set("X-Frame-Options", "DENY");
        request.sendResponseHeaders(code, bytes.length);
        request.getResponseBody().write(bytes);
    }

    private String page() {
        return """
            <!doctype html><html lang="zh-CN"><meta charset="utf-8">
            <title>迁移手动故障测试</title>
            <body><h1>迁移手动故障测试</h1>
            <p>点击执行后，可随时切断连接。等待本轮 FAILED，再恢复连接并点击续跑。</p>
            <p>继续使用原 job-id、配置和源数据范围。恢复网络不会自动重新执行迁移。</p>
            <button data-action="run">执行 / 续跑</button>
            <button data-action="source/cut">断开 source</button>
            <button data-action="source/restore">恢复 source</button>
            <button data-action="target/cut">断开 target</button>
            <button data-action="target/restore">恢复 target</button>
            <button data-action="finish">成功后结束测试</button>
            <button data-action="abort">终止测试（失败）</button>
            <pre id="status"></pre><pre id="message"></pre>
            <script>
            const base = location.pathname;
            async function refresh() {
              try { const r = await fetch(base + 'status'); document.getElementById('status').textContent = await r.text(); }
              catch (e) { document.getElementById('status').textContent = '控制连接已关闭'; }
            }
            document.querySelectorAll('button').forEach(button => button.onclick = async () => {
              try {
                const r = await fetch(base + button.dataset.action, {method:'POST'});
                document.getElementById('message').textContent = r.status + ' ' + await r.text();
                await refresh();
              } catch(e) { document.getElementById('message').textContent = '控制连接已关闭'; }
            });
            refresh(); setInterval(refresh, 1000);
            </script></body></html>
            """;
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        server.stop(0);
        http.shutdownNow();
        worker.shutdownNow();
        // Keep the test resources alive until migration workers have cleaned up their transactions.
        try (var scope = new ExecutorScope(worker)) { }
    }
}
