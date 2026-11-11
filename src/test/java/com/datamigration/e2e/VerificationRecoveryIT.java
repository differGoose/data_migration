package com.datamigration.e2e;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.datamigration.api.*;
import com.datamigration.support.DatabaseFixture;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Separate JVMs, durable file-backed synthetic H2 databases, and the public Runner entry point. */
class VerificationRecoveryIT {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"VERIFYING", "VERIFICATION_MISMATCH"})
    void crashAndNewJvmRecoveryUseOnlyPersistedAudit(String boundary) throws Exception {
        String source = "jdbc:h2:file:" + directory.resolve("source").toString().replace('\\','/') + ";WRITE_DELAY=0";
        String target = "jdbc:h2:file:" + directory.resolve("target").toString().replace('\\','/') + ";WRITE_DELAY=0";
        DatabaseFixture.initializeAudit(target);
        DatabaseFixture.execute(source,"CREATE TABLE A(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
        DatabaseFixture.execute(source,"INSERT INTO A SELECT X,'row-' || X FROM SYSTEM_RANGE(1,21)");
        DatabaseFixture.execute(target,"CREATE TABLE A(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
        Path yaml = directory.resolve("migration.yml");
        Files.writeString(yaml,"""
                data-migration:
                  job-id: verification-crash
                  tables:
                    - name: A
                      target-table: A
                      primary-key: [ID]
                      batch-size: 7
                      verification: {enabled: true, sample-percent: 100}
                      select-sql: SELECT ID,NAME FROM A
                      insert-sql: INSERT INTO A(ID,NAME) VALUES (?,?)
                      fields:
                        - {source-column: ID, target-jdbc-type: BIGINT}
                        - {source-column: NAME, target-jdbc-type: VARCHAR}
                """);
        assertThat(child(yaml,source,target,boundary,"crash.log")).isEqualTo(73);
        try (var connection = DatabaseFixture.connection(target); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT state FROM migration_audit WHERE batch_no=0")) {
            assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo(boundary);
        }
        if (boundary.equals("VERIFICATION_MISMATCH")) {
            assertThat(child(yaml,source,target,"RECOVER","still-mismatch.log")).isNotZero();
            try (var connection = DatabaseFixture.connection(target); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM A WHERE NAME='bad'")) {
                result.next(); assertThat(result.getLong(1)).isEqualTo(21);
            }
            DatabaseFixture.execute(target,"UPDATE A SET NAME='row-' || ID");
        }
        assertThat(child(yaml,source,target,"RECOVER","recover.log")).isZero();
        try (var connection = DatabaseFixture.connection(target); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM A WHERE NAME='row-' || ID")) {
            result.next(); assertThat(result.getLong(1)).isEqualTo(21);
        }
        try (var connection = DatabaseFixture.connection(target); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM migration_audit WHERE batch_no=0 AND state='SUCCEEDED' AND committed_rows=21")) {
            result.next(); assertThat(result.getLong(1)).isEqualTo(1);
        }
    }
    private int child(Path yaml,String source,String target,String mode,String log) throws Exception {
        Path java = Path.of(System.getProperty("java.home"),"bin","java.exe");
        if (!Files.exists(java)) java = java.resolveSibling("java");
        var process = new ProcessBuilder(java.toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                Host.class.getName(),yaml.toUri().toString(),source,target,mode)
                .redirectErrorStream(true).redirectOutput(directory.resolve(log).toFile()).start();
        try {
            assertThat(process.waitFor(30,TimeUnit.SECONDS)).as("child output: %s",directory.resolve(log)).isTrue();
            return process.exitValue();
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); } }
    }
    public static final class Host {
        public static void main(String[] args) {
            Logger logger = (Logger) LoggerFactory.getLogger("com.datamigration");
            var appender = new AppenderBase<ILoggingEvent>() {
                @Override protected void append(ILoggingEvent event) {
                    String message = event.getFormattedMessage();
                    if (message.startsWith("Content verification started")) {
                        if (args[3].equals("VERIFYING")) Runtime.getRuntime().halt(73);
                        if (args[3].equals("VERIFICATION_MISMATCH")) try {
                            DatabaseFixture.execute(args[2],"UPDATE A SET NAME='bad'");
                        } catch (Exception failure) { throw new IllegalStateException(failure); }
                    }
                    if (args[3].equals("VERIFICATION_MISMATCH") && message.startsWith("Table migration completed") && message.contains("status=VERIFICATION_MISMATCH")) Runtime.getRuntime().halt(73);
                }
            };
            appender.start(); logger.addAppender(appender);
            try (var runner = MigrationRunner.builder()
                    .source(new DriverManagerDataSource(args[1],"sa",""),2)
                    .target(new DriverManagerDataSource(args[2],"sa",""),2)
                    .configLocations(args[0]).build()) {
                runner.migrate();
            } finally { logger.detachAppender(appender); appender.stop(); }
        }
    }
}
