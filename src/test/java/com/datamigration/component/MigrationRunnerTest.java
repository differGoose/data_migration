package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationRunnerTest {
    @TempDir Path directory;
    Path configuration() throws Exception {
        Path path=directory.resolve("migration.yml");
        Files.writeString(path, """
            data-migration:
              job-id: startup
              strategy:
                max-concurrent-tables: 1
              tables:
                - name: A
                  batch-size: 17
                  target-table: A
                  primary-key: [ID]
                  select-sql: SELECT ID, NAME FROM A ORDER BY ID
                  insert-sql: INSERT INTO A VALUES (?, ?)
                  fields:
                    - {source-column: ID, target-jdbc-type: BIGINT}
                    - {source-column: NAME, target-jdbc-type: VARCHAR}
                - name: B
                  batch-size: 11
                  target-table: B
                  primary-key: [ID]
                  select-sql: SELECT ID, NAME FROM B ORDER BY ID
                  insert-sql: INSERT INTO B VALUES (?, ?)
                  fields:
                    - {source-column: ID, target-jdbc-type: BIGINT}
                    - {source-column: NAME, target-jdbc-type: VARCHAR}
            """);
        return path;
    }
    MigrationRunner.Builder runner(DatabaseFixture db, Path config) {
        return MigrationRunner.builder().source(new DriverManagerDataSource(db.sourceUrl,"sa",""),4)
                .target(new DriverManagerDataSource(db.targetUrl,"sa",""),4).configLocations(config.toUri().toString());
    }
    @Test void perTableBoundariesResumeAndCompletionAreIndependent() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",40); db.seed("B",25);
            db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>20)");
            Path yaml=configuration();
            try(var migration=runner(db,yaml).build()) {
                assertThatThrownBy(migration::migrate).isInstanceOf(MigrationException.class);
                assertThat(db.scalar("SELECT COUNT(*) FROM B")).isEqualTo(25);
                db.target("ALTER TABLE A DROP CONSTRAINT bad");
                migration.migrate(); migration.migrate();
            }
            try(var restarted=runner(db,yaml).build()) { restarted.migrate(); }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(40);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='startup' AND task_name='A' AND batch_no>0")).isEqualTo(3);
            assertThat(db.scalar("SELECT MAX(expected_rows) FROM migration_audit WHERE job_id='startup' AND task_name='A' AND batch_no>0")).isEqualTo(17);
            assertThat(db.scalar("SELECT MAX(expected_rows) FROM migration_audit WHERE job_id='startup' AND task_name='B' AND batch_no>0")).isEqualTo(11);
            assertThat(db.scalar("SELECT MIN(expected_rows) FROM migration_audit WHERE job_id='startup' AND task_name='B' AND batch_no>0")).isEqualTo(3);
        }
    }
    @Test void configurationIsFrozenAndArgumentsOverrideFiles() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",3); db.seed("B",2); Path yaml=configuration();
            Path override=directory.resolve("override.yml");
            Files.writeString(override,"data-migration:\n  job-id: from-file\n");
            try(var migration=runner(db,yaml).configLocations(yaml.toUri().toString(),override.toUri().toString())
                    .arguments("--data-migration.job-id=from-argument").build()) {
                migration.migrate();
                Files.writeString(yaml,"data-migration:\n  invalid: true\n");
                migration.migrate();
            }
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='from-argument' AND batch_no=0")).isEqualTo(2);
        }
    }
    @Test void failurePreventsHostStartupAndSuccessfulMigrationPrecedesBusinessBeans() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",3); db.seed("B",2); Path yaml=configuration();
            AtomicBoolean started=new AtomicBoolean();
            db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>1)");
            assertThatThrownBy(() -> {
                try(var migration=runner(db,yaml).build()) { migration.migrate(); }
                started.set(true);
            }).isInstanceOf(MigrationException.class);
            assertThat(started).isFalse();
            db.target("ALTER TABLE A DROP CONSTRAINT bad");
            try(var migration=runner(db,yaml).converter((value,type) -> {
                assertThat(started).isFalse();
                return new com.datamigration.conversion.JdbcValueConverter().convert(value,type);
            }).build()) { migration.migrate(); }
            started.set(true);
            assertThat(started).isTrue();
        }
    }
    @Test void concurrentCallIsRejectedAndCloseStopsWorkButNotHostDataSources() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",40); db.seed("B",3);
            var source=spy(new DriverManagerDataSource(db.sourceUrl,"sa",""));
            var target=spy(new DriverManagerDataSource(db.targetUrl,"sa",""));
            CountDownLatch entered=new CountDownLatch(1);
            var executor=Executors.newSingleThreadExecutor();
            var runner=MigrationRunner.builder().source(source,4).target(target,4).configLocations(configuration().toUri().toString())
                .converter((value,type) -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException("stopped"); }
                    return value;
                }).build();
            try {
                var future=executor.submit(runner::migrate);
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(runner::migrate).hasMessageContaining("already running");
                runner.close();
                assertThatThrownBy(() -> future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThatThrownBy(runner::migrate).hasMessageContaining("closed");
                try(var c=target.getConnection()) { assertThat(c.isValid(1)).isTrue(); }
            } finally { runner.close(); executor.shutdownNow(); }
        }
    }
    @Test void invalidBuildersAndInsufficientCapacityFailWithoutBorrowing() throws Exception {
        var ds=mock(javax.sql.DataSource.class);
        assertThatThrownBy(() -> MigrationRunner.builder().environment(new MockEnvironment()).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MigrationRunner.builder().source(ds,0).target(ds,1).environment(new MockEnvironment()).build()).hasMessageContaining("positive");
        assertThatThrownBy(() -> MigrationRunner.builder().source(ds,1).target(ds,1).environment(new MockEnvironment()).configLocations("x").build()).hasMessageContaining("either");
        try(var runner=MigrationRunner.builder().source(ds,1).target(ds,1).configLocations(configuration().toUri().toString()).build()) {
            assertThatThrownBy(runner::migrate).hasMessageContaining("Insufficient pool capacity");
        }
        verifyNoInteractions(ds);
    }
    @Test void endpointFailureIsSanitizedAndCanBeRetried() throws Exception {
        var ds=mock(javax.sql.DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("private-password"));
        try(var runner=MigrationRunner.builder().source(ds,4).target(ds,4).configLocations(configuration().toUri().toString()).build()) {
            assertThatThrownBy(runner::migrate).hasMessage("Cannot initialize migration endpoints").hasNoCause();
            assertThatThrownBy(runner::migrate).hasMessage("Cannot initialize migration endpoints");
        }
    }
    @Test void changingTableBatchSizeCannotReuseExistingCheckpoint() throws Exception {
        try(var db=new DatabaseFixture()) {
            db.seed("A",40); db.seed("B",25); Path yaml=configuration();
            try(var first=runner(db,yaml).build()) { first.migrate(); }
            Files.writeString(yaml,Files.readString(yaml).replace("batch-size: 17","batch-size: 19"));
            try(var changed=runner(db,yaml).build()) {
                assertThatThrownBy(changed::migrate).isInstanceOf(MigrationException.class);
            }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(40);
            assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE job_id='startup' AND task_name='A' AND batch_no=0 AND state='SUCCEEDED'")).isEqualTo(1);
        }
    }
}
