package com.datamigration.support;

import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.properties.MigrationConfiguration;
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
                databases = new TcpDatabases(output.resolve("databases"));
                databases.seed(settings.seedRows());
                sourceHost = "127.0.0.1"; targetHost = "127.0.0.1";
                sourcePort = URI.create(databases.sourceUrl().substring("jdbc:h2:".length())).getPort();
                targetPort = URI.create(databases.targetUrl().substring("jdbc:h2:".length())).getPort();
            }
            require(sourceHost != null && targetHost != null && sourcePort > 0 && targetPort > 0,
                    "Configure both proxy upstream hosts and ports");
            try (var source = new TcpFaultProxy(sourceHost, sourcePort, settings.sourceProxy().port());
                 var target = new TcpFaultProxy(targetHost, targetPort, settings.targetProxy().port())) {
                overrides.put("resilience.source-proxy.upstream-host",sourceHost);
                overrides.put("resilience.source-proxy.upstream-port",sourcePort);
                overrides.put("resilience.target-proxy.upstream-host",targetHost);
                overrides.put("resilience.target-proxy.upstream-port",targetPort);
                overrides.put("resilience.source-proxy.port", source.port());
                overrides.put("resilience.target-proxy.port", target.port());
                overrides.put("e2e.source-proxy.port", source.port());
                overrides.put("e2e.target-proxy.port", target.port());
                if (databases != null) {
                    var hostProperties = new HashMap<>(databases.properties());
                    hostProperties.remove("data-migration.job-id");
                    overrides.putAll(hostProperties);
                    overrides.put("host-databases.source.jdbc-url", databases.sourceUrl().replace(":" + sourcePort + "/", ":" + source.port() + "/"));
                    overrides.put("host-databases.target.jdbc-url", databases.targetUrl().replace(":" + targetPort + "/", ":" + target.port() + "/"));
                }
                writeManifest(location, overrides, output);
                E2EValidation.Source baseline = sourceSnapshot(location, overrides);
                require(source.forwardedConnections() > 0, "Source JDBC endpoint must route through the configured proxy");
                boolean alreadyComplete = triggerComplete(location, overrides);
                require(target.forwardedConnections() > 0, "Target JDBC endpoint must route through the configured proxy");
                writeOverrides(output.resolve("runtime.properties"), overrides);
                Path control = Files.createDirectory(output.resolve("control"));
                boolean fired = false;
                if (!alreadyComplete) {
                    Process process = worker(location, output, control, "fault");
                    try {
                        long deadline = System.nanoTime() + settings.phaseTimeout().toNanos();
                        while (!Files.exists(control.resolve("ready")) && process.isAlive()) {
                            require(System.nanoTime() < deadline, "Timed out waiting for configured business commit boundary");
                            Thread.sleep(25);
                        }
                        if (Files.exists(control.resolve("ready"))) {
                            fired = true;
                            Map<String,RowDigest> boundary = null;
                            if (settings.scenario() == ResilienceSettings.Scenario.APP_BEFORE_COMMIT
                                    || settings.scenario() == ResilienceSettings.Scenario.APP_AFTER_COMMIT)
                                boundary = triggerTargets(location,overrides);
                            switch (settings.scenario()) {
                                case SOURCE_OUTAGE -> source.cut();
                                case TARGET_OUTAGE -> target.cut();
                                case APP_BEFORE_COMMIT, APP_AFTER_COMMIT -> process.destroyForcibly();
                            }
                            if (boundary == null) {
                                Files.writeString(control.resolve("release"), "continue");
                                require(process.waitFor(settings.phaseTimeout().toMillis(), TimeUnit.MILLISECONDS),
                                        "Database outage did not fail within phase-timeout");
                                require(process.exitValue() == 20, "Expected migration failure after database outage");
                            } else {
                                require(process.waitFor(settings.phaseTimeout().toMillis(), TimeUnit.MILLISECONDS), "Host JVM did not terminate");
                                require(process.exitValue() != 0, "Host crash must terminate abnormally");
                                require(triggerTargets(location,overrides).equals(boundary), "Host crash changed committed boundary data");
                            }
                        } else {
                            require(process.exitValue() == 0, "Migration failed before reaching fault boundary; inspect private worker log");
                        }
                    } finally { stop(process); source.restore(); target.restore(); }
                }
                require(sourceSnapshot(location,overrides).equals(baseline), "Source changed during fault phase");
                runSuccess(location, output, control, settings.phaseTimeout(), "resume");
                var completed = validate(location, overrides, baseline);
                runSuccess(location, output, control, settings.phaseTimeout(), "repeat");
                require(validate(location, overrides, baseline).equals(completed), "Repeated migration changed data or audit totals/batches");
                String report = "status=PASSED\nenvironment=" + settings.environment() + "\nscenario=" + settings.scenario()
                        + "\nfaultTriggered=" + fired + "\nalreadyCompleted=" + alreadyComplete
                        + "\nresumed=true\nrepeatIdempotent=true\nvalidatedTables=" + baseline.expected().size()
                        + "\nvalidatedRows=" + baseline.expected().values().stream().mapToLong(RowDigest::rows).sum() + "\n";
                Files.writeString(output.resolve("result.properties"), report);
            }
        } catch (Exception | AssertionError failure) {
            Files.writeString(output.resolve("result.properties"), "status=FAILED\nscenario=" + settings.scenario() + "\n");
            throw failure;
        } finally { if (databases != null) databases.close(); }
    }

    private static E2EValidation.Source sourceSnapshot(String location,Map<String,Object> overrides) throws Exception {
        try(var context=ResilienceContexts.open(location,overrides)) {
            var settings=ResilienceSettings.read(context.getEnvironment());
            var config=context.getBean(MigrationConfiguration.class).get();
            return E2EValidation.source(context.getBean(DataSourceConnections.class),config.toPlan(),
                    context.getBean(ValueConverter.class),settings.validation(),DatabaseE2E.seconds(settings.phaseTimeout()));
        }
    }
    private static boolean triggerComplete(String location,Map<String,Object> overrides) throws Exception {
        try(var context=ResilienceContexts.open(location,overrides)) {
            var settings=ResilienceSettings.read(context.getEnvironment());
            var config=context.getBean(MigrationConfiguration.class).get();
            var trigger=config.toPlan().tables().stream().filter(t -> t.name().equals(settings.triggerTask()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown trigger-task"));
            require(trigger.workerCount()==1,"Fault trigger task needs one worker for deterministic boundaries");
            try(var c=context.getBean(DataSourceConnections.class).openTargetConnection();
                var st=c.prepareStatement("SELECT state FROM "+com.datamigration.sql.SqlSupport.table(config.strategy().audit().tableName())
                    +" WHERE job_id=? AND task_name=? AND batch_no=0")) {
                st.setString(1,config.jobId()); st.setString(2,trigger.name());
                st.setQueryTimeout(DatabaseE2E.seconds(settings.phaseTimeout()));
                try(var rows=st.executeQuery()) { return rows.next() && "SUCCEEDED".equals(rows.getString(1)); }
            }
        }
    }
    private static Map<String,RowDigest> triggerTargets(String location,Map<String,Object> overrides) throws Exception {
        try(var context=ResilienceContexts.open(location,overrides)) {
            var settings=ResilienceSettings.read(context.getEnvironment());
            var plan=context.getBean(MigrationConfiguration.class).get().toPlan();
            var trigger=plan.tables().stream().filter(t -> t.name().equals(settings.triggerTask())).findFirst().orElseThrow();
            return E2EValidation.targets(context.getBean(DataSourceConnections.class),
                    new com.datamigration.model.MigrationPlan(plan.jobId(),plan.strategy(),List.of(trigger)),
                    context.getBean(ValueConverter.class),settings.validation(),DatabaseE2E.seconds(settings.phaseTimeout()));
        }
    }
    private static E2EValidation.Result validate(String location,Map<String,Object> overrides,E2EValidation.Source baseline) throws Exception {
        try(var context=ResilienceContexts.open(location,overrides)) {
            var settings=ResilienceSettings.read(context.getEnvironment());
            var plan=context.getBean(MigrationConfiguration.class).get().toPlan();
            return E2EValidation.complete(context.getBean(DataSourceConnections.class),plan,context.getBean(ValueConverter.class),
                    settings.validation(),DatabaseE2E.seconds(settings.phaseTimeout()),baseline);
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
        var properties = new Properties();
        values.forEach((k, v) -> {
            if (k.matches("(?:e2e|resilience)\\.(?:source|target)-proxy\\.(?:port|upstream-host|upstream-port)")
                    || k.equals("e2e.external-host") || k.equals("resilience.environment") || k.equals("resilience.scenario")
                    || k.matches("host-databases\\.(?:source|target)\\.jdbc-url"))
                properties.setProperty(k,v.toString());
        });
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
