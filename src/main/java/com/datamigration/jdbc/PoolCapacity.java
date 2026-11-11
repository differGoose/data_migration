package com.datamigration.jdbc;

import com.datamigration.diagnostics.MigrationLog;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.sql.DataSource;

/** Optional pool metadata adapters. Never borrow connections or change host pool configuration. */
public final class PoolCapacity {
    private PoolCapacity() { }
    public record Limit(int capacity, Integer maximumPoolSize, DataSource pool) { }

    public static Limit resolve(DataSource source, String endpoint, Integer declared) {
        try (var diagnostic = MigrationLog.phase("CAPACITY_CHECK", endpoint, "data-migration.strategy.max-concurrent-tables")) {
            var metadata = inspect(source, endpoint);
            if (metadata == null) {
                if (declared == null) throw new IllegalArgumentException("Cannot determine " + endpoint
                        + " pool capacity; provide a supported pool or explicit migration capacity");
                return new Limit(declared, null, null);
            }
            return new Limit(declared == null ? metadata.capacity() : Math.min(declared, metadata.capacity()),
                    metadata.capacity(), metadata.pool());
        }
    }
    private static Limit inspect(DataSource source, String endpoint) {
        Set<DataSource> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        DataSource current = source;
        for (int depth = 0; current != null && depth < 16 && visited.add(current); depth++) {
            if (hasType(current, "com.zaxxer.hikari.HikariDataSource")) {
                try {
                    int maximum = ((Number)current.getClass().getMethod("getMaximumPoolSize").invoke(current)).intValue();
                    if (maximum < 1) throw new IllegalArgumentException();
                    return new Limit(maximum, maximum, current);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    throw new IllegalArgumentException("Cannot read " + endpoint
                            + " maximumPoolSize; configure a positive pool maximum before migration");
                }
            }
            if (hasType(current, "org.springframework.jdbc.datasource.DelegatingDataSource")) {
                try {
                    current = (DataSource)current.getClass().getMethod("getTargetDataSource").invoke(current);
                    continue;
                } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
            }
            try {
                Class<?> poolType = Class.forName("com.zaxxer.hikari.HikariDataSource", false, Thread.currentThread().getContextClassLoader() == null
                        ? PoolCapacity.class.getClassLoader() : Thread.currentThread().getContextClassLoader());
                if (!current.isWrapperFor(poolType)) return null;
                Object unwrapped = current.unwrap(poolType);
                if (!(unwrapped instanceof DataSource dataSource)) return null;
                current = dataSource;
            } catch (ClassNotFoundException | java.sql.SQLException | RuntimeException failure) { return null; }
        }
        return null;
    }
    private static boolean hasType(DataSource source, String name) {
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(name)) return true;
        return false;
    }
}
