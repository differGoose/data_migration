package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.support.*;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 is prepared by the caller, never by DatabaseE2E. This is NOT real Oracle/PG acceptance. */
class ExternalDatabaseE2ETest {
    @TempDir Path directory;
    static HikariDataSource pool(String url) {
        var ds=new HikariDataSource(); ds.setJdbcUrl(url); ds.setUsername("sa"); ds.setPassword("");
        ds.setMaximumPoolSize(8); ds.setMinimumIdle(0); return ds;
    }
    Path config(String job,String extra) throws Exception {
        Path yaml=directory.resolve(job+".yml");
        Files.writeString(yaml,"""
            data-migration:
              job-id: JOB
              strategy:
                max-concurrent-tables: 1
              tables:
                - name: A
                  batch-size: 7
                  target-table: A
                  primary-key: [ID]
                  select-sql: SELECT ID, NAME FROM A ORDER BY ID
                  insert-sql: INSERT INTO A VALUES (?, ?)
                  fields:
                    - {source-column: ID, target-jdbc-type: BIGINT}
                    - {source-column: NAME, target-jdbc-type: VARCHAR}
                - name: B
                  batch-size: 3
                  target-table: B
                  primary-key: [ID]
                  select-sql: SELECT ID, NAME FROM B ORDER BY ID
                  insert-sql: INSERT INTO B VALUES (?, ?)
                  fields:
                    - {source-column: ID, target-jdbc-type: BIGINT}
                    - {source-column: NAME, target-jdbc-type: VARCHAR}
            e2e:
              report-directory: REPORT
              validation:
                A:
                  source-sql: SELECT ID, NAME FROM A ORDER BY ID
                  target-sql: SELECT ID, NAME FROM A ORDER BY ID
                B:
                  source-sql: SELECT ID, NAME FROM B ORDER BY ID
                  target-sql: SELECT ID, NAME FROM B ORDER BY ID
            """.replace("JOB",job).replace("REPORT",directory.resolve("reports").toString().replace('\\','/'))+extra);
        return yaml;
    }
    @Test void suppliedDataSourcesRemainOpenAndDifferentJobsUseFormalLogic() throws Exception {
        try(var db=new DatabaseFixture(); var source=spy(pool(db.sourceUrl)); var target=spy(pool(db.targetUrl))) {
            db.seed("A",20); db.seed("B",10); Path yaml=config("external","");
            var first=DatabaseE2E.run(source,8,target,8,yaml.toUri().toString());
            assertThat(first.validation().audits().get("A").batches()).hasSize(3);
            assertThat(first.validation().audits().get("B").batches()).hasSize(4);
            assertThat(Files.readString(first.directory().resolve("result.properties"))).contains("status=PASSED");
            DatabaseE2E.run(source,8,target,8,yaml.toUri().toString());
            verify(source,never()).close(); verify(target,never()).close();
            // A different job on nonempty APPEND targets fails in the real engine, not an empty-table precheck.
            Path next=config("another","");
            assertThatThrownBy(() -> DatabaseE2E.run(source,8,target,8,next.toUri().toString())).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
            // Explicit reimport of a new job is supported exactly as in production.
            Files.writeString(next,Files.readString(next).replace("target-table: A","mode: CLEAR_AND_REIMPORT\n      target-table: A")
                .replace("target-table: B","mode: CLEAR_AND_REIMPORT\n      target-table: B"));
            // A new migration rule uses a separate job; recovery always keeps its original configuration.
            Files.writeString(next,Files.readString(next).replace("job-id: another","job-id: reimport"));
            DatabaseE2E.run(source,8,target,8,next.toUri().toString());
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
        }
    }
    @Test void failedCallRetainsPartialProgressAndSameConfigurationResumes() throws Exception {
        try(var db=new DatabaseFixture(); var source=pool(db.sourceUrl); var target=pool(db.targetUrl)) {
            db.seed("A",20); db.seed("B",10); db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>9)");
            Path yaml=config("resume","");
            assertThatThrownBy(() -> DatabaseE2E.run(source,8,target,8,yaml.toUri().toString())).isInstanceOf(MigrationException.class);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(7);
            db.target("ALTER TABLE A DROP CONSTRAINT bad");
            DatabaseE2E.run(source,8,target,8,yaml.toUri().toString());
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
        }
    }
    @Test void configuredExpectedSubsetAllowsDeliberatelySkippedRows() throws Exception {
        try(var db=new DatabaseFixture(); var source=pool(db.sourceUrl); var target=pool(db.targetUrl)) {
            db.seed("A",20); db.seed("B",10); db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>9)");
            Path yaml=config("skip","");
            Files.writeString(yaml,Files.readString(yaml).replace("max-concurrent-tables: 1","max-concurrent-tables: 1\n    skip-limit: 1")
                .replace("source-sql: SELECT ID, NAME FROM A ORDER BY ID","source-sql: SELECT ID, NAME FROM A WHERE ID<>9 ORDER BY ID"));
            var result=DatabaseE2E.run(source,8,target,8,yaml.toUri().toString());
            assertThat(result.validation().audits().get("A").skipped()).isEqualTo(1);
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(19);
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class SuppliedSources {
        @Bean(destroyMethod="close") @MigrationSource(capacity=8)
        HikariDataSource source(Environment env) { return pool(env.getRequiredProperty("caller.source")); }
        @Bean(destroyMethod="close") @MigrationTarget(capacity=8)
        HikariDataSource target(Environment env) { return pool(env.getRequiredProperty("caller.target")); }
    }
    @Test void configuredHostUsesExternalBeansAndMissingClassesNeverFallBackToH2() throws Exception {
        Path missing=config("missing","");
        assertThatThrownBy(() -> DatabaseE2E.runConfigured(missing.toUri().toString())).hasMessageContaining("no H2 fallback");
        try(var db=new DatabaseFixture()) {
            db.seed("A",20); db.seed("B",10);
            Path yaml=config("configured","caller:\n  source: '"+db.sourceUrl+"'\n  target: '"+db.targetUrl+"'\n");
            String text=Files.readString(yaml).replace("e2e:\n","e2e:\n  configuration-classes: ["+SuppliedSources.class.getName()+"]\n");
            Files.writeString(yaml,text);
            Path output=DatabaseE2E.runConfigured(yaml.toUri().toString());
            assertThat(Files.readString(output.resolve("result.properties"))).contains("status=PASSED");
        }
    }
    @Test void timeoutReportsFailureAndNeverClosesCallerDataSources() throws Exception {
        var source=mock(javax.sql.DataSource.class); var target=mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenAnswer(inv -> {
            try { Thread.sleep(10000); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException("cancelled"); }
            throw new SQLException("unavailable");
        });
        Path yaml=config("timeout","");
        Files.writeString(yaml,Files.readString(yaml).replace("e2e:\n","e2e:\n  phase-timeout: 50ms\n"));
        assertThatThrownBy(() -> DatabaseE2E.run(source,8,target,8,yaml.toUri().toString()))
                .hasMessageContaining("deadline exceeded");
        try(var reports=Files.walk(directory.resolve("reports"))) {
            Path result=reports.filter(p -> p.getFileName().toString().equals("result.properties")).findFirst().orElseThrow();
            assertThat(Files.readString(result)).contains("status=FAILED").doesNotContain("PASSED");
        }
        verifyNoInteractions(target);
    }
}
