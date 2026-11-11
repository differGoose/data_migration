package com.datamigration.component;

import com.datamigration.api.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.properties.MigrationConfiguration;
import com.datamigration.support.DatabaseFixture;
import java.sql.JDBCType;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class VerificationRunnerTest {
    private org.springframework.mock.env.MockEnvironment configuration() {
        return new AsyncMigrationTest().configuration("A")
                .withProperty("data-migration.tables[0].insert-sql", "INSERT INTO A(ID,NAME) VALUES (?,?)")
                .withProperty("data-migration.tables[0].verification.enabled", "true")
                .withProperty("data-migration.tables[0].verification.sample-percent", "100");
    }
    @Test void bindingHasDisabledDefaultAndPreservesPerTableDecimalPercentage() {
        var defaults = new MigrationConfiguration(new AsyncMigrationTest().configuration("A")).get();
        assertThat(defaults.toPlan().tables().get(0).verification().enabled()).isFalse();
        var env = configuration().withProperty("data-migration.tables[0].verification.sample-percent", "0.5");
        assertThat(new MigrationConfiguration(env).get().toPlan().tables().get(0).verification().samples(101)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "100.01", "not-a-number"})
    void invalidPercentagesFailBinding(String value) {
        assertThatThrownBy(() -> new MigrationConfiguration(configuration()
                .withProperty("data-migration.tables[0].verification.sample-percent", value)).get()).isInstanceOf(RuntimeException.class);
    }
    @Test void missingPercentageAndSkipAllowanceAreRejected() {
        // Build the missing percentage case without overwriting it with a default.
        var env = new AsyncMigrationTest().configuration("A").withProperty("data-migration.tables[0].verification.enabled", "true");
        assertThatThrownBy(() -> new MigrationConfiguration(env).get()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new MigrationConfiguration(configuration()
                .withProperty("data-migration.strategy.skip-limit", "1")).get()).isInstanceOf(RuntimeException.class);
    }
    @Test void publicSyncAndAsyncDeduplicateDuringVerificationAndOnlyReverifyAfterMismatch() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A",4);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var once = new AtomicBoolean();
            try (var runner = new AsyncMigrationTest().runner(db,configuration()).converter((value,type) -> {
                if (type == JDBCType.BIGINT && db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no=0 AND state='VERIFYING'") == 1
                        && once.compareAndSet(false,true)) {
                    entered.countDown(); AsyncMigrationTest.waitOn(release);
                }
                return new JdbcValueConverter().convert(value,type);
            }).build()) {
                try {
                    var execution = runner.migrateAsync();
                    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                    assertThat(execution.progress().tables().get(0).state()).isEqualTo("VERIFYING");
                    assertThat(runner.migrate()).isSameAs(execution);
                    assertThat(runner.migrateAsync()).isSameAs(execution);
                    db.target("UPDATE A SET NAME='bad'"); release.countDown();
                    assertThatThrownBy(() -> execution.completion().toCompletableFuture().get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                    assertThat(execution.progress().tables().get(0).state()).isEqualTo("VERIFICATION_MISMATCH");
                    assertThat(execution.progress().committedRows()).isEqualTo(4);
                    assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(4);
                    assertThatThrownBy(runner::migrate).isInstanceOf(MigrationException.class);
                    db.target("UPDATE A SET NAME='row-' || ID");
                    var retry = runner.migrate();
                    assertThat(retry).isNotSameAs(execution);
                    assertThat(retry.progress().status()).isEqualTo(MigrationProgress.Status.SUCCEEDED);
                    var checked = runner.migrateAsync();
                    assertThat(checked).isNotSameAs(retry);
                    checked.completion().toCompletableFuture().get(5,TimeUnit.SECONDS);
                } finally { release.countDown(); }
            }
        }
    }
    @Test void hostCanManuallyResetTargetAndTaskAuditThenMigrateWithNewRunner() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A",4); db.target("INSERT INTO A VALUES(999,'old')");
            var host = new AsyncMigrationTest(); var env = configuration();
            try (var runner = host.runner(db,env).build()) {
                assertThatThrownBy(runner::migrate).isInstanceOf(MigrationException.class);
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(5);
                assertThat(db.scalar("SELECT COUNT(*) FROM migration_audit WHERE batch_no=0 AND state='VERIFICATION_MISMATCH'")).isEqualTo(1);
            }
            // Only the host/test fixture performs this reset, after the previous Runner is closed.
            try (var connection = db.openTargetConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM A");
                statement.executeUpdate("DELETE FROM migration_audit WHERE job_id='async-job' AND task_name='A'");
                connection.commit();
            }
            try (var runner = host.runner(db,env).build()) {
                assertThat(runner.migrate().progress().status()).isEqualTo(MigrationProgress.Status.SUCCEEDED);
            }
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(4);
        }
    }
}
