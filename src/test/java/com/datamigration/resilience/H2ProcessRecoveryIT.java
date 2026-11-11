package com.datamigration.resilience;

import com.datamigration.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * Terminates the migration JVM at a batch commit boundary or after sequence
 * calibration. A fresh JVM resumes from the persisted data and audit records.
 */
class H2ProcessRecoveryIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"before","after"})
    void newJvmRecoversFromAbruptExitAtCommitBoundary(String crash) throws Exception {
        try (var databases=new TcpDatabases(directory.resolve("db"))) {
            databases.seed(10_000);
            assertThat(run(databases,crash)).isEqualTo(73);
            try (var connection=DatabaseFixture.connection(databases.targetUrl());
                 var statement=connection.createStatement();
                 var result=statement.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(crash.equals("after") ? 1000 : 0);
            }
            assertThat(run(databases,"resume")).isZero();
            MigrationAssertions.assertEquivalent(databases);
        }
    }

    @Test
    void newJvmResumesAfterSequenceCalibrationBeforeSuccessAudit() throws Exception {
        try (var databases = new TcpDatabases(directory.resolve("db"))) {
            databases.seed(10_000);
            DatabaseFixture.execute(databases.targetUrl(), "CREATE SEQUENCE CUSTOMER_SEQ START WITH 1");
            String sequence = "--test.recovery.sequence=true";
            assertThat(run(databases, "after-sequence", sequence)).isEqualTo(73);
            MigrationAssertions.assertCompletedDataState(databases, "FINALIZING");
            MigrationAssertions.assertEquivalent(databases);
            var batches = MigrationAssertions.businessCheckpoints(databases);
            assertThat(batches).hasSize(10);
            assertThat(sequenceNextValue(databases)).isEqualTo(10_001);

            DatabaseFixture.execute(databases.targetUrl(), "ALTER SEQUENCE CUSTOMER_SEQ RESTART WITH 20001");
            assertThat(run(databases, "resume", sequence)).isZero();
            MigrationAssertions.assertCompletedDataState(databases, "SUCCEEDED");
            MigrationAssertions.assertEquivalent(databases);
            assertThat(MigrationAssertions.businessCheckpoints(databases)).isEqualTo(batches);
            assertThat(sequenceNextValue(databases)).isEqualTo(20_001);
        }
    }

    private long sequenceNextValue(TcpDatabases databases) throws Exception {
        try (var connection = DatabaseFixture.connection(databases.targetUrl());
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES"
                     + " WHERE SEQUENCE_SCHEMA='PUBLIC' AND SEQUENCE_NAME='CUSTOMER_SEQ'")) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private int run(TcpDatabases databases,String mode,String... options) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path output=directory.resolve(mode+".log");
        var command = new ArrayList<>(List.of(java,"-cp",classpath,H2RecoveryWorker.class.getName(),mode,
                "--spring.config.location=classpath:recovery.yml","--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off","--data-migration.job-id=e2e",
                "--host-databases.source.jdbc-url="+databases.sourceUrl(),
                "--host-databases.source.username=sa","--host-databases.source.password=",
                "--host-databases.target.jdbc-url="+databases.targetUrl(),
                "--host-databases.target.username=sa","--host-databases.target.password="));
        command.addAll(List.of(options));
        Process process=new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(45,TimeUnit.SECONDS)).withFailMessage("Child process timed out: "+output).isTrue();
            int code=process.exitValue();
            assertThat(code).withFailMessage(new String(Files.readAllBytes(output),StandardCharsets.UTF_8)).isIn(0,73);
            return code;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

}
