package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.support.DatabaseFixture;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static com.datamigration.support.MigrationAwait.await;
import static com.datamigration.component.TransactionRecoveryTest.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class AsyncMigrationTest {
    MockEnvironment configuration(String... names) {
        var env = new MockEnvironment().withProperty("data-migration.job-id", "async-job")
                .withProperty("data-migration.strategy.max-concurrent-tables", "1")
                .withProperty("data-migration.strategy.transient-retry-count", "0");
        for (int i = 0; i < names.length; i++) {
            String key = "data-migration.tables[" + i + "].";
            env.withProperty(key + "name", names[i]).withProperty(key + "target-table", names[i])
                    .withProperty(key + "select-sql", "SELECT ID,NAME FROM " + names[i])
                    .withProperty(key + "insert-sql", "INSERT INTO " + names[i] + " VALUES (?,?)")
                    .withProperty(key + "primary-key[0]", "ID").withProperty(key + "batch-size", "7")
                    .withProperty(key + "fields[0].source-column", "ID").withProperty(key + "fields[0].target-jdbc-type", "BIGINT")
                    .withProperty(key + "fields[1].source-column", "NAME").withProperty(key + "fields[1].target-jdbc-type", "VARCHAR");
        }
        return env;
    }
    MigrationRunner.Builder runner(DatabaseFixture db, MockEnvironment env) {
        return MigrationRunner.builder().source(new DriverManagerDataSource(db.sourceUrl, "sa", ""), 4)
                .target(new DriverManagerDataSource(db.targetUrl, "sa", ""), 4).environment(env);
    }
    static void waitOn(CountDownLatch gate) throws SQLException {
        try { gate.await(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException("interrupted", "57014"); }
    }
    @Test void asyncFirstAndConcurrentMixedCallsReuseHandleAndReportOnlyConfirmedRows() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20); db.seed("B", 3);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var ids = new AtomicInteger(); var callers = Executors.newFixedThreadPool(8);
            var migration = runner(db, configuration("A", "B")).converter((value, type) -> {
                if (type == JDBCType.BIGINT && ids.incrementAndGet() == 8) { entered.countDown(); waitOn(release); }
                return new JdbcValueConverter().convert(value, type);
            }).build();
            try {
                MigrationExecution first = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), migration::migrateAsync);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 16; i++) {
                    boolean sync = i % 2 == 0;
                    assertThat(callers.submit(() -> sync ? migration.migrate() : migration.migrateAsync()).get(1, TimeUnit.SECONDS)).isSameAs(first);
                }
                var snapshot = first.progress();
                assertThat(snapshot.status()).isEqualTo(MigrationProgress.Status.RUNNING);
                assertThat(snapshot.tables().get(0).totalRows()).isEqualTo(20L);
                assertThat(snapshot.committedRows()).isEqualTo(7);
                assertThat(snapshot.tables().get(1).totalRows()).isNull();
                assertThat(snapshot.tables().get(1).state()).isEqualTo("PENDING");
                assertThatThrownBy(() -> snapshot.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
                release.countDown();
                var result = await(first);
                assertThat(result.runId()).isEqualTo(snapshot.runId());
                assertThat(first.progress().status()).isEqualTo(MigrationProgress.Status.SUCCEEDED);
                assertThat(first.progress().committedRows()).isEqualTo(23);
                assertThat(snapshot.committedRows()).isEqualTo(7);
                assertThat(migration.migrateAsync()).isSameAs(first);
                assertThat(migration.migrate()).isSameAs(first);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
                assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no>0")).isEqualTo(4);
            } finally { release.countDown(); migration.close(); callers.shutdownNow(); }
        }
    }
    @Test void synchronousFirstWaitsButAsyncAndSyncDuplicatesReturnImmediately() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 9);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var caller = Executors.newSingleThreadExecutor();
            var migration = runner(db, configuration("A")).converter((value, type) -> {
                entered.countDown(); waitOn(release); return value;
            }).build();
            try {
                var initial = caller.submit(migration::migrate);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(initial.isDone()).isFalse();
                var shared = migration.migrateAsync();
                assertThat(migration.migrate()).isSameAs(shared);
                shared.completion().toCompletableFuture().cancel(true);
                assertThat(shared.progress().status()).isEqualTo(MigrationProgress.Status.RUNNING);
                release.countDown();
                assertThat(initial.get(5, TimeUnit.SECONDS)).isSameAs(shared);
                assertThat(shared.progress().status()).isEqualTo(MigrationProgress.Status.SUCCEEDED);
            } finally { release.countDown(); migration.close(); caller.shutdownNow(); }
        }
    }
    @Test void failureCanBeRetriedWithNewHandleAndAuditResumeAcrossRunnerRestart() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20); db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>9)");
            try (var migration = runner(db, configuration("A")).build()) {
                var failed = migration.migrateAsync();
                assertThatThrownBy(() -> await(failed)).isInstanceOf(MigrationException.class);
                assertThat(failed.progress().status()).isEqualTo(MigrationProgress.Status.FAILED);
                assertThat(failed.progress().committedRows()).isEqualTo(7);
                assertThat(failed.progress().tables().get(0).sanitizedError()).startsWith("SQLSTATE=");
                db.target("ALTER TABLE A DROP CONSTRAINT bad");
                var retry = migration.migrateAsync();
                assertThat(retry).isNotSameAs(failed);
                await(retry);
                assertThat(retry.progress().committedRows()).isEqualTo(20);
                assertThat(failed.progress().status()).isEqualTo(MigrationProgress.Status.FAILED);
            }
            try (var restarted = runner(db, configuration("A")).build()) {
                var result = restarted.migrate();
                assertThat(result.progress().tables().get(0).totalRows()).isEqualTo(20L);
                assertThat(result.progress().committedRows()).isEqualTo(20);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(20);
            }
        }
    }
    @Test void keylessProgressDoesNotCountUncommittedBatches() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20);
            var env = configuration("A");
            env.getPropertySources().forEach(source -> {
                if (source instanceof org.springframework.core.env.MapPropertySource map)
                    map.getSource().remove("data-migration.tables[0].primary-key[0]");
            });
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ids = new AtomicInteger();
            var migration = runner(db, env).converter((value, type) -> {
                if (type == JDBCType.BIGINT && ids.incrementAndGet() == 8) { entered.countDown(); waitOn(release); }
                return value;
            }).build();
            try {
                var execution = migration.migrateAsync();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.progress().committedRows()).isZero();
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isZero();
                release.countDown(); await(execution);
                assertThat(execution.progress().committedRows()).isEqualTo(20);
            } finally { release.countDown(); migration.close(); }
        }
    }
    @Test void sequenceFinalizationIsVisibleAndFailureDoesNotLoseCommittedCounts() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 10); db.target("CREATE SEQUENCE A_SEQ");
            var env = configuration("A").withProperty("data-migration.tables[0].sequence.enabled", "true")
                    .withProperty("data-migration.tables[0].sequence.name", "A_SEQ")
                    .withProperty("data-migration.tables[0].sequence.target-column", "ID");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var target = mock(DataSource.class);
            when(target.getConnection()).thenAnswer(call -> {
                var real = DriverManager.getConnection(db.targetUrl, "sa", "");
                return proxy(Connection.class, real, (method, args) -> {
                    if (method.getName().equals("prepareStatement") && ((String)args[0]).contains("INFORMATION_SCHEMA.SEQUENCES")) {
                        entered.countDown(); waitOn(release);
                        throw new SQLException("private-password-token", "42501");
                    }
                    return invoke(real, method, args);
                });
            });
            var migration = runner(db, env).target(target, 4).build();
            try {
                var execution = migration.migrateAsync();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.progress().status()).isEqualTo(MigrationProgress.Status.RUNNING);
                assertThat(execution.progress().tables().get(0).state()).isEqualTo("FINALIZING");
                assertThat(execution.progress().committedRows()).isEqualTo(10);
                release.countDown();
                assertThatThrownBy(() -> await(execution)).isInstanceOf(MigrationException.class);
                assertThat(execution.progress().tables().get(0).state()).isEqualTo("FINALIZATION_FAILED");
                assertThat(execution.progress().toString()).doesNotContain("private-password-token");
            } finally { release.countDown(); migration.close(); }
        }
    }
    @Test void closeCancelsWaitsForCleanupAndKeepsHostDataSourcesOpen() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 10);
            var entered = new CountDownLatch(1);
            var source = new DriverManagerDataSource(db.sourceUrl, "sa", "");
            var target = new DriverManagerDataSource(db.targetUrl, "sa", "");
            var migration = runner(db, configuration("A")).source(source, 4).target(target, 4).converter((value, type) -> {
                entered.countDown(); waitOn(new CountDownLatch(1)); return value;
            }).build();
            var execution = migration.migrateAsync();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            migration.close(); migration.close();
            assertThat(execution.completion().toCompletableFuture()).isCompletedExceptionally();
            assertThat(execution.progress().status()).isEqualTo(MigrationProgress.Status.FAILED);
            assertThatThrownBy(migration::migrateAsync).hasMessageContaining("closed");
            assertThatThrownBy(migration::migrate).hasMessageContaining("closed");
            try (var s = source.getConnection(); var t = target.getConnection()) {
                assertThat(s.isValid(1) && t.isValid(1)).isTrue();
            }
            try (var next = runner(db, configuration("A")).build()) { await(next); }
        }
    }
    @Test void springSingletonSharesSyncAndAsyncStateWithoutStartingTwice() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("B", 10);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            new GlobalMigrationExecutionTest().context(db)
                .withBean(com.datamigration.conversion.ValueConverter.class, () -> (value, type) -> {
                    entered.countDown(); waitOn(release); return value;
                }).run(context -> {
                    var service = context.getBean(MigrationService.class);
                    var first = service.migrateAsync();
                    try {
                        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(service.migrate()).isSameAs(first);
                        assertThat(service.migrateAsync()).isSameAs(first);
                        release.countDown(); await(first);
                        assertThat(first.progress().committedRows()).isEqualTo(10);
                        assertThat(service.migrate()).isSameAs(first);
                    } finally { release.countDown(); }
                });
        }
    }
    @Test void interruptingInitialSyncCallerCancelsAttemptAndReleasesGlobalPermit() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 10);
            var entered = new CountDownLatch(1); var callerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var caller = Executors.newSingleThreadExecutor();
            try (var migration = runner(db, configuration("A")).converter((value, type) -> {
                entered.countDown(); waitOn(new CountDownLatch(1)); return value;
            }).build()) {
                var outcome = caller.submit(() -> {
                    callerThread.set(Thread.currentThread());
                    try { migration.migrate(); return false; }
                    catch (IllegalStateException expected) { return Thread.currentThread().isInterrupted(); }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                callerThread.get().interrupt();
                assertThat(outcome.get(5, TimeUnit.SECONDS)).isTrue();
                try (var next = runner(db, configuration("A")).build()) { await(next); }
            } finally { caller.shutdownNow(); }
        }
    }
    @Test void converterCanCloseRunnerWithoutWaitingForItsOwnWorker() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 10);
            var owner = new java.util.concurrent.atomic.AtomicReference<MigrationRunner>();
            try (var migration = runner(db, configuration("A")).converter((value, type) -> {
                owner.get().close(); return value;
            }).build()) {
                owner.set(migration);
                var execution = migration.migrateAsync();
                assertThatThrownBy(() -> execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);
            }
            try (var next = runner(db, configuration("A")).build()) { await(next); }
        }
    }
    @Test void confirmedCommitAfterDriverErrorAndSkippedRowsAreCountedExactlyOnce() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20); db.target("ALTER TABLE A ADD CONSTRAINT skip_one CHECK(ID<>9)");
            var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
            var target = mock(DataSource.class);
            when(target.getConnection()).thenAnswer(call -> {
                var real = DriverManager.getConnection(db.targetUrl, "sa", "");
                var business = new java.util.concurrent.atomic.AtomicBoolean();
                return proxy(Connection.class, real, (method, args) -> {
                    Object value = invoke(real, method, args);
                    if (method.getName().equals("prepareStatement") && ((String)args[0]).startsWith("INSERT INTO A")) {
                        var statement = (PreparedStatement)value;
                        return proxy(PreparedStatement.class, statement, (operation, parameters) -> {
                            Object result = invoke(statement, operation, parameters);
                            if (operation.getName().equals("executeBatch") || operation.getName().equals("executeUpdate")) business.set(true);
                            return result;
                        });
                    }
                    if (method.getName().equals("commit") && business.get() && failOnce.compareAndSet(true, false))
                        throw new SQLException("private-commit-secret", "08006");
                    return value;
                });
            });
            try (var migration = runner(db, configuration("A").withProperty("data-migration.strategy.skip-limit", "1"))
                    .target(target, 4).build()) {
                var execution = migration.migrateAsync(); await(execution);
                assertThat(failOnce).isFalse();
                assertThat(execution.progress().committedRows()).isEqualTo(19);
                assertThat(execution.progress().skippedRows()).isEqualTo(1);
                assertThat(execution.progress().toString()).doesNotContain("private-commit-secret");
            }
        }
    }

    @Test void connectionReturnFailureAfterSuccessfulCommitDoesNotDoubleProgress() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20);
            var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var ids = new AtomicInteger(); var target = mock(DataSource.class);
            when(target.getConnection()).thenAnswer(call -> {
                var real = DriverManager.getConnection(db.targetUrl, "sa", "");
                var business = new java.util.concurrent.atomic.AtomicBoolean();
                var committed = new java.util.concurrent.atomic.AtomicBoolean();
                return proxy(Connection.class, real, (method, args) -> {
                    Object value = invoke(real, method, args);
                    if (method.getName().equals("prepareStatement") && ((String)args[0]).startsWith("INSERT INTO A")) {
                        var statement = (PreparedStatement)value;
                        return proxy(PreparedStatement.class, statement, (operation, parameters) -> {
                            Object result = invoke(statement, operation, parameters);
                            if (operation.getName().equals("executeBatch")) business.set(true);
                            return result;
                        });
                    }
                    if (method.getName().equals("commit") && business.get()) committed.set(true);
                    if (method.getName().equals("close") && committed.get() && failOnce.compareAndSet(true, false))
                        throw new SQLException("connection return failed", "08006");
                    return value;
                });
            });
            var migration = runner(db, configuration("A")).target(target, 4).converter((value, type) -> {
                if (type == JDBCType.BIGINT && ids.incrementAndGet() == 8) { entered.countDown(); waitOn(release); }
                return value;
            }).build();
            try {
                var execution = migration.migrateAsync();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(failOnce).isFalse();
                assertThat(execution.progress().committedRows()).isEqualTo(7);
                release.countDown(); await(execution);
                assertThat(execution.progress().committedRows()).isEqualTo(20);
            } finally { release.countDown(); migration.close(); }
        }
    }

}
