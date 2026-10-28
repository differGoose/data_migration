package com.datamigration.support;

import com.datamigration.api.*;
import com.datamigration.autoconfigure.MigrationConnectionPools;
import com.datamigration.conversion.ValueConverter;
import com.datamigration.execution.JdbcMigrationEngine;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.properties.MigrationConfiguration;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real disposable host JVM. No secrets, SQL or business rows in its command line/output. */
public final class ResilienceWorker {
    public static void main(String[] args) {
        int exit = 0;
        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args[1]))) { properties.load(reader); }
            Map<String, Object> overrides = new HashMap<>();
            properties.forEach((k, v) -> overrides.put(k.toString(), v));
            try (var context = ResilienceContexts.open(args[0], overrides)) {
                var config = context.getBean(MigrationConfiguration.class).get();
                var pools = context.getBean(MigrationConnectionPools.class);
                var settings = ResilienceSettings.read(context.getEnvironment());
                Set<String> inserts = new HashSet<>();
                config.toPlan().tables().stream().filter(t -> t.name().equals(settings.triggerTask()))
                        .forEach(t -> inserts.add(t.insertSql()));
                java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
                AtomicBoolean fired = new AtomicBoolean();
                boolean inject = args[2].equals("fault");
                Path control = Path.of(args[3]);
                ConnectionAccess access = new ConnectionAccess() {
                    public Connection openSourceConnection() throws SQLException { return pools.openSourceConnection(); }
                    public Connection openTargetConnection() throws SQLException {
                        return wrap(pools.openTargetConnection(), inserts, () -> {
                            if (!inject || commits.incrementAndGet() != settings.triggerCommit()
                                    || !fired.compareAndSet(false, true)) return;
                            try {
                                Files.writeString(control.resolve("ready"), "commit-boundary");
                                long deadline = System.nanoTime() + settings.phaseTimeout().toNanos();
                                while (!Files.exists(control.resolve("release"))) {
                                    if (System.nanoTime() >= deadline) throw new SQLException("Fault controller timed out");
                                    Thread.sleep(20);
                                }
                            } catch (java.io.IOException e) { throw new SQLException("Fault control failed"); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException("Fault control interrupted"); }
                        }, settings.scenario() == ResilienceSettings.Scenario.APP_BEFORE_COMMIT);
                    }
                };
                String identity = config.source().jdbcUrl() + "|" + config.source().username() + "|"
                        + config.target().jdbcUrl() + "|" + config.target().username();
                try (var engine = new JdbcMigrationEngine(access, config.source().pool().maximumPoolSize(),
                        config.target().pool().maximumPoolSize(), identity, context.getBean(ValueConverter.class))) {
                    engine.execute(config.toPlan());
                }
            }
        } catch (MigrationException e) { exit = 20; }
        catch (Throwable e) { exit = 21; }
        System.out.println("resilience-worker-exit=" + exit);
        System.exit(exit);
    }
    @FunctionalInterface interface Boundary { void reached() throws SQLException; }
    static Connection wrap(Connection connection, Set<String> inserts, Boundary boundary, boolean before) {
        AtomicBoolean business = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(ResilienceWorker.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    boolean commit = method.getName().equals("commit") && business.get();
                    if (commit && before) boundary.reached();
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement") && inserts.contains(args[0])) {
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(ResilienceWorker.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, operation, values) -> {
                                    Object value = invoke(statement, operation, values);
                                    if (operation.getName().equals("executeBatch") || operation.getName().equals("executeUpdate")) business.set(true);
                                    return value;
                                });
                    }
                    if (commit && !before) boundary.reached();
                    if (commit || method.getName().equals("rollback")) business.set(false);
                    return result;
                });
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
