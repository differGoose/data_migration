package com.datamigration.support;

import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.model.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.execution.JdbcMigrationEngine;
import org.h2.tools.RunScript;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public final class DatabaseFixture implements ConnectionAccess, AutoCloseable {
    public final String sourceUrl;
    public final String targetUrl;
    public DatabaseFixture() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        sourceUrl = "jdbc:h2:mem:source" + id + ";DB_CLOSE_DELAY=-1";
        targetUrl = "jdbc:h2:mem:target" + id + ";DB_CLOSE_DELAY=-1";
        initializeAudit(targetUrl);
    }
    public static void initializeAudit(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var stream = DatabaseFixture.class.getResourceAsStream("/db/h2/migration-audit.sql");
             var reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
    }
    public Connection openSourceConnection() throws SQLException { return connection(sourceUrl); }
    public Connection openTargetConnection() throws SQLException { return connection(targetUrl); }
    public static Connection connection(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url, "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }
    public void source(String sql) throws SQLException { execute(sourceUrl, sql); }
    public void target(String sql) throws SQLException { execute(targetUrl, sql); }
    public static void execute(String url, String sql) throws SQLException {
        try (Connection connection = connection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql); connection.commit();
        }
    }
    public long scalar(String sql) throws SQLException {
        try (Connection connection = openTargetConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
    public void seed(String name, int size) throws SQLException {
        source("CREATE TABLE " + name + "(ID BIGINT PRIMARY KEY, NAME VARCHAR(100))");
        target("CREATE TABLE " + name + "(ID BIGINT PRIMARY KEY, NAME VARCHAR(100))");
        try (var connection = openSourceConnection(); var insert = connection.prepareStatement("INSERT INTO " + name + " VALUES (?,?)")) {
            for (int i = 1; i <= size; i++) {
                insert.setLong(1, i); insert.setString(2, "row-" + i); insert.addBatch();
            }
            insert.executeBatch(); connection.commit();
        }
    }
    public TableTask task(String name) {
        return new TableTask(name, "SELECT ID, NAME FROM " + name + " ORDER BY ID",
                "INSERT INTO " + name + "(ID,NAME) VALUES (?,?)",
                List.of(new FieldMapping("ID", JDBCType.BIGINT), new FieldMapping("NAME", JDBCType.VARCHAR)),
                name, List.of("ID"), 2, TableTask.Mode.APPEND_RESUME, new SequenceSpec(false, null, null), 100);
    }
    public MigrationPlan plan(TableTask... tasks) { return plan("job", 0, tasks); }
    public MigrationPlan plan(String job, long skip, TableTask... tasks) {
        return new MigrationPlan(job, new MigrationStrategy( skip, 2,
                Duration.ofMillis(1), Duration.ofMillis(2), 2, true, "migration_audit", 16*1024, 8*1024*1024), List.of(tasks));
    }
    public JdbcMigrationEngine engine() { return engine(this); }
    public JdbcMigrationEngine engine(ConnectionAccess access) {
        return new JdbcMigrationEngine(access, 20, 20, new JdbcValueConverter());
    }
    @Override public void close() throws SQLException {
        for (String url : List.of(sourceUrl, targetUrl)) {
            try (var connection = connection(url); var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }
}
