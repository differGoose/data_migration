package com.datamigration.support;

import org.h2.tools.Server;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Local, independently stoppable servers. Never uses production JDBC credentials. */
public final class TcpDatabases implements AutoCloseable {
    public final Path directory;
    public final boolean tls;
    private Server source;
    private Server target;
    private int sourcePort;
    private int targetPort;
    public TcpDatabases(Path directory, boolean tls) throws Exception {
        this.directory = directory.toAbsolutePath(); this.tls = tls;
        Files.createDirectories(this.directory);
        source = start(0); sourcePort = source.getPort();
        try { target = start(0); targetPort = target.getPort(); }
        catch (Exception failure) { source.stop(); throw failure; }
        try { DatabaseFixture.initializeAudit(targetUrl()); }
        catch (Exception failure) { close(); throw failure; }
    }
    private Server start(int port) throws SQLException {
        List<String> args = new ArrayList<>(List.of("-tcpPort", Integer.toString(port),
                "-baseDir", directory.toString(), "-ifNotExists"));
        if (tls) args.add("-tcpSSL");
        return Server.createTcpServer(args.toArray(String[]::new)).start();
    }
    public String sourceUrl() { return "jdbc:h2:" + (tls ? "ssl" : "tcp") + "://localhost:" + sourcePort + "/./source"; }
    public String targetUrl() { return "jdbc:h2:" + (tls ? "ssl" : "tcp") + "://localhost:" + targetPort + "/./target"; }
    public void stopSource() { source.stop(); }
    public void stopTarget() { target.stop(); }
    public void restartSource() throws SQLException { source = start(sourcePort); }
    public void restartTarget() throws SQLException { target = start(targetPort); }

    public void seed(int count) throws SQLException {
        DatabaseFixture.execute(sourceUrl(), "CREATE TABLE CUSTOMER(ID BIGINT PRIMARY KEY,NAME VARCHAR(100),AMOUNT DECIMAL(20,4))");
        DatabaseFixture.execute(targetUrl(), "CREATE TABLE CUSTOMER(ID BIGINT PRIMARY KEY,NAME VARCHAR(100),AMOUNT DECIMAL(20,4))");
        try (var connection = DatabaseFixture.connection(sourceUrl());
             var insert = connection.prepareStatement("INSERT INTO CUSTOMER VALUES (?,?,?)")) {
            for (int i = 1; i <= count; i++) {
                insert.setLong(1, i);
                insert.setString(2, i % 11 == 0 ? null : "客户-" + i);
                insert.setBigDecimal(3, new java.math.BigDecimal(i).movePointLeft(4));
                insert.addBatch();
                if (i % 200 == 0) insert.executeBatch();
            }
            insert.executeBatch(); connection.commit();
        }
    }
    public Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("data-migration.job-id", "e2e"),
                Map.entry("data-migration.source.jdbc-url", sourceUrl()),
                Map.entry("data-migration.source.username", "sa"),
                Map.entry("data-migration.source.password", ""),
                Map.entry("data-migration.source.driver-class-name", "org.h2.Driver"),
                Map.entry("data-migration.source.pool.maximum-pool-size", "6"),
                Map.entry("data-migration.target.jdbc-url", targetUrl()),
                Map.entry("data-migration.target.username", "sa"),
                Map.entry("data-migration.target.password", ""),
                Map.entry("data-migration.target.driver-class-name", "org.h2.Driver"),
                Map.entry("data-migration.target.pool.maximum-pool-size", "6"),
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("spring.main.web-application-type", "none"));
    }
    /** Small independent fixture: includes a multi-megabyte BLOB, an empty BLOB and SQL NULL. */
    public void seedDocuments() throws SQLException {
        String ddl = "CREATE TABLE DOCUMENTS(ID BIGINT PRIMARY KEY, CONTENT BLOB, NOTES CLOB)";
        DatabaseFixture.execute(sourceUrl(), ddl);
        DatabaseFixture.execute(targetUrl(), ddl);
        byte[] content = new byte[3 * 1024 * 1024];
        new Random(42).nextBytes(content);
        try (var connection = DatabaseFixture.connection(sourceUrl());
             var insert = connection.prepareStatement("INSERT INTO DOCUMENTS VALUES (?,?,?)")) {
            for (int id = 1; id <= 3; id++) {
                insert.setLong(1, id);
                if (id == 3) insert.setNull(2, Types.BLOB);
                else insert.setBinaryStream(2, new java.io.ByteArrayInputStream(id == 1 ? content : new byte[0]));
                if (id == 3) insert.setNull(3, Types.CLOB);
                else insert.setCharacterStream(3, new java.io.StringReader("文档-".repeat(id * 1000)));
                insert.executeUpdate();
            }
            connection.commit();
        }
    }
    @Override public void close() { if (source != null) source.stop(); if (target != null) target.stop(); }
}
