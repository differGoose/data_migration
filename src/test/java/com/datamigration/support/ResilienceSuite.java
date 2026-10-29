package com.datamigration.support;

import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.model.TableTask;
import com.datamigration.properties.MigrationConfiguration;
import com.datamigration.support.TcpDatabases;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared orchestration for H2 and operator-prepared DEV tables. No DEV DDL or cleanup. */
public final class ResilienceSuite {
    private ResilienceSuite() {}
    public static void run(String location, Map<String, Object> initial, Path output) throws Exception {
        Files.createDirectories(output);
        Map<String, Object> overrides = new HashMap<>(initial);
        ResilienceSettings settings;
        try (var context = ResilienceContexts.open(location, overrides)) { settings = ResilienceSettings.read(context.getEnvironment()); }
        TcpDatabases databases = null;
        try {
            String sourceHost = settings.sourceProxy().upstreamHost(), targetHost = settings.targetProxy().upstreamHost();
            int sourcePort = settings.sourceProxy().upstreamPort(), targetPort = settings.targetProxy().upstreamPort();
            if (settings.environment().equals("h2")) {
                databases = new TcpDatabases(output.resolve("databases"), false);
                databases.seed(settings.seedRows());
                sourceHost = "127.0.0.1"; targetHost = "127.0.0.1";
                sourcePort = URI.create(databases.sourceUrl().substring("jdbc:h2:".length())).getPort();
                targetPort = URI.create(databases.targetUrl().substring("jdbc:h2:".length())).getPort();
            }
            require(sourceHost != null && targetHost != null && sourcePort > 0 && targetPort > 0,
                    "Configure both proxy upstream hosts and ports");
            try (var source = new TcpFaultProxy(sourceHost, sourcePort, settings.sourceProxy().port());
                 var target = new TcpFaultProxy(targetHost, targetPort, settings.targetProxy().port())) {
                overrides.put("resilience.source-proxy.port", source.port());
                overrides.put("resilience.target-proxy.port", target.port());
                if (databases != null) {
                    overrides.putAll(databases.properties());
                    overrides.put("host-databases.source.jdbc-url", databases.sourceUrl().replace(":" + sourcePort + "/", ":" + source.port() + "/"));
                    overrides.put("host-databases.target.jdbc-url", databases.targetUrl().replace(":" + targetPort + "/", ":" + target.port() + "/"));
                    overrides.put("data-migration.job-id", "resilience-" + UUID.randomUUID());
                }
                writeManifest(location, overrides, output);
                Map<String, RowDigest> baseline = inspect(location, overrides, null, true, false);
                require(source.forwardedConnections() > 0 && target.forwardedConnections() > 0,
                        "Both JDBC endpoints must route through the configured proxies");
                writeOverrides(output.resolve("runtime.properties"), overrides);
                Path control = Files.createDirectory(output.resolve("control"));
                Process process = worker(location, output, control, "fault");
                try {
                    long deadline = System.nanoTime() + settings.phaseTimeout().toNanos();
                    while (!Files.exists(control.resolve("ready"))) {
                        require(process.isAlive(), "Worker exited before fault boundary; inspect private worker log");
                        require(System.nanoTime() < deadline, "Timed out waiting for first business commit boundary");
                        Thread.sleep(25);
                    }
                    switch (settings.scenario()) {
                        case SOURCE_OUTAGE -> source.cut();
                        case TARGET_OUTAGE -> target.cut();
                        case APP_BEFORE_COMMIT, APP_AFTER_COMMIT -> process.destroyForcibly();
                    }
                    if (settings.scenario() == ResilienceSettings.Scenario.SOURCE_OUTAGE
                            || settings.scenario() == ResilienceSettings.Scenario.TARGET_OUTAGE) {
                        Files.writeString(control.resolve("release"), "continue");
                        require(process.waitFor(settings.phaseTimeout().toMillis(), TimeUnit.MILLISECONDS),
                                "Database outage did not fail within phase-timeout");
                        require(process.exitValue() == 20, "Expected migration failure after database outage");
                    } else {
                        require(process.waitFor(settings.phaseTimeout().toMillis(), TimeUnit.MILLISECONDS), "Host JVM did not terminate");
                        require(process.exitValue() != 0, "Host crash must terminate abnormally");
                    }
                } finally {
                    stop(process); source.restore(); target.restore();
                }
                // Wait for server-side transaction/connection cleanup by opening a new context.
                inspect(location, overrides, baseline, false, false);
                runSuccess(location, output, control, settings.phaseTimeout(), "resume");
                inspect(location, overrides, baseline, false, true);
                // A completed job must be idempotent, including its audit totals.
                runSuccess(location, output, control, settings.phaseTimeout(), "repeat");
                inspect(location, overrides, baseline, false, true);
                String report = "status=PASSED\nenvironment=" + settings.environment() + "\nscenario=" + settings.scenario()
                        + "\nfaultTriggered=true\nresumed=true\nrepeatIdempotent=true\nvalidatedTables=" + baseline.size()
                        + "\nvalidatedRows=" + baseline.values().stream().mapToLong(RowDigest::rows).sum() + "\n";
                Files.writeString(output.resolve("result.properties"), report);
            }
        } catch (Exception | AssertionError failure) {
            Files.writeString(output.resolve("result.properties"), "status=FAILED\nscenario=" + settings.scenario() + "\n");
            throw failure;
        } finally { if (databases != null) databases.close(); }
    }

    private static Map<String, RowDigest> inspect(String location, Map<String, Object> overrides,
            Map<String, RowDigest> expected, boolean preflight, boolean complete) throws Exception {
        try (var context = ResilienceContexts.open(location, overrides)) {
            var settings = ResilienceSettings.read(context.getEnvironment());
            var config = context.getBean(MigrationConfiguration.class).get();
            var pools = context.getBean(DataSourceConnections.class);
            var converter = context.getBean(ValueConverter.class);
            var trigger = config.toPlan().tables().stream().filter(t -> t.name().equals(settings.triggerTask()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown trigger-task"));
            require(trigger.workerCount() == 1, "Trigger task requires worker-count=1 for deterministic commit numbering");
            require(config.strategy().audit().enabled(), "Resilience recovery requires audit enabled");
            require(config.strategy().skipLimit() == 0, "Resilience equality checks require skip-limit=0");
            require(settings.validation() != null, "Configure validation SQL for every enabled task");
            Map<String, RowDigest> actual = new LinkedHashMap<>();
            try (var source = pools.openSourceConnection(); var target = pools.openTargetConnection()) {
                for (var task : config.toPlan().tables()) {
                    require(task.keyed() && task.mode() == TableTask.Mode.APPEND_RESUME,
                            "Resilience tests require keyed APPEND_RESUME tasks; no automatic table clearing");
                    var validation = settings.validation().get(task.name());
                    require(validation != null && validation.sourceSql() != null && validation.targetSql() != null,
                            "Missing task validation SQL");
                    int timeout = (int) Math.max(1, Math.min(Integer.MAX_VALUE, settings.phaseTimeout().toSeconds()));
                    RowDigest sourceDigest = RowDigest.read(source, validation.sourceSql(), task, converter, timeout);
                    RowDigest targetDigest = RowDigest.read(target, validation.targetSql(), task, converter, timeout);
                    actual.put(task.name(), sourceDigest);
                    if (preflight) {
                        require(sourceDigest.rows() > (long) task.batchSize()
                                        * (task.name().equals(settings.triggerTask()) ? settings.triggerCommit() : task.workerCount()),
                                "Source range must exceed the configured trigger commit so a fault interrupts unfinished work");
                        require(targetDigest.rows() == 0, "DEV target validation range must be empty before a new scenario");
                        try (var st = target.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM "
                                + com.datamigration.sql.SqlSupport.table(task.targetTable()))) {
                            rs.next(); require(rs.getLong(1) == 0, "Use an empty dedicated target table for each new scenario");
                        }
                    } else {
                        require(sourceDigest.equals(expected.get(task.name())), "Source changed during resilience test");
                        if (complete) require(targetDigest.equals(sourceDigest), "Target row count or field checksum differs from source");
                        else if (task.name().equals(settings.triggerTask())) {
                            require(targetDigest.rows() < sourceDigest.rows(), "Fault must leave unfinished migration work");
                            if (settings.scenario() == ResilienceSettings.Scenario.APP_BEFORE_COMMIT
                                    || settings.scenario() == ResilienceSettings.Scenario.APP_AFTER_COMMIT) {
                                long commits = settings.triggerCommit()
                                        - (settings.scenario() == ResilienceSettings.Scenario.APP_BEFORE_COMMIT ? 1 : 0);
                                require(targetDigest.rows() == commits * task.batchSize(),
                                        "Host crash did not preserve the expected business commit boundary");
                            }
                        }
                    }
                    String audit = com.datamigration.sql.SqlSupport.table(config.strategy().audit().tableName());
                    if (complete) {
                        try (var statement = target.prepareStatement("SELECT COALESCE(SUM(committed_rows),0),"
                                + "COALESCE(SUM(skipped_rows),0) FROM " + audit
                                + " WHERE job_id=? AND task_name=? AND batch_no>0 AND state='SUCCEEDED'")) {
                            statement.setString(1, config.jobId()); statement.setString(2, task.name());
                            statement.setQueryTimeout(timeout);
                            try (var rows = statement.executeQuery()) {
                                rows.next();
                                require(rows.getLong(1) == sourceDigest.rows() && rows.getLong(2) == 0,
                                        "Batch audit totals differ from recovered data");
                            }
                        }
                    }
                    try (var statement = target.prepareStatement("SELECT state,committed_rows,skipped_rows FROM " + audit
                            + " WHERE job_id=? AND task_name=? AND batch_no=0")) {
                        statement.setString(1, config.jobId()); statement.setString(2, task.name());
                        try (var rows = statement.executeQuery()) {
                            if (preflight) require(!rows.next(), "Use a new job-id for a new scenario; preserve it for resume");
                            else if (complete) {
                                require(rows.next(), "Missing audit summary after recovery");
                                require("SUCCEEDED".equals(rows.getString(1)) && rows.getLong(2) == sourceDigest.rows()
                                        && rows.getLong(3) == 0, "Audit summary does not match recovered data");
                            }
                        }
                    }
                }
            }
            return actual;
        }
    }
    private static void writeManifest(String location, Map<String, Object> overrides, Path output) throws Exception {
        try (var context = ResilienceContexts.open(location, overrides)) {
            var config = context.getBean(MigrationConfiguration.class).get();
            var settings = ResilienceSettings.read(context.getEnvironment());
            // Strict allowlist: no URLs, SQL, user names, passwords, provider output or business values.
            var manifest = new Properties();
            manifest.setProperty("run-id", UUID.randomUUID().toString());
            manifest.setProperty("started-at", java.time.Instant.now().toString());
            manifest.setProperty("job-id", config.jobId());
            manifest.setProperty("environment", settings.environment());
            manifest.setProperty("scenario", settings.scenario().name());
            manifest.setProperty("trigger-task", settings.triggerTask());
            manifest.setProperty("trigger-commit", Integer.toString(settings.triggerCommit()));
            manifest.setProperty("phases", "fault,resume,repeat");
            manifest.setProperty("source-proxy-port", overrides.get("resilience.source-proxy.port").toString());
            manifest.setProperty("target-proxy-port", overrides.get("resilience.target-proxy.port").toString());
            config.toPlan().tables().forEach(task -> manifest.setProperty("batch-size." + task.name(), Integer.toString(task.batchSize())));
            try (var writer = Files.newBufferedWriter(output.resolve("manifest.properties"))) {
                manifest.store(writer, "Secret-free execution manifest");
            }
        }
    }
    private static void writeOverrides(Path path, Map<String, Object> values) throws Exception {
        var properties = new Properties(); values.forEach((k, v) -> properties.setProperty(k, v.toString()));
        try (var writer = Files.newBufferedWriter(path)) { properties.store(writer, "Local test runtime; do not commit"); }
    }
    private static Process worker(String location, Path output, Path control, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath, ResilienceWorker.class.getName(), location,
                output.resolve("runtime.properties").toAbsolutePath().toString(), mode, control.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.resolve(mode + ".log").toFile()).start();
    }
    private static void runSuccess(String location, Path output, Path control, Duration timeout, String mode) throws Exception {
        Process process = worker(location, output, control, mode);
        try {
            require(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS), "Recovery worker timed out");
            require(process.exitValue() == 0, "Recovery worker failed; inspect private worker log");
        } finally { stop(process); }
    }
    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
