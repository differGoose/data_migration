package com.datamigration.component;

import static com.datamigration.support.MigrationAwait.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.datamigration.api.*;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.model.*;
import com.datamigration.support.DatabaseFixture;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationLoggingTest {
    private static final String SECRET = "private-password-token-business-value";
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.datamigration");
    private Level previous;
    private final Queue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();
    private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); events.add(event); }
    };
    @TempDir Path directory;

    @BeforeEach void startCapture() { previous = logger.getLevel(); logger.setLevel(Level.INFO); capture.start(); logger.addAppender(capture); }
    @AfterEach void stopCapture() {
        logger.detachAppender(capture); capture.stop(); logger.setLevel(previous);
        assertThat(messages()).doesNotContain(SECRET);
        assertThat(events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
    private String messages() { return events.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }
    private MockEnvironment configuration(String... tables) {
        var env = new MockEnvironment().withProperty("data-migration.job-id", "logging-job")
                .withProperty("data-migration.strategy.transient-retry-count", "0");
        for (int i = 0; i < tables.length; i++) {
            String key = "data-migration.tables[" + i + "].";
            env.withProperty(key + "name", tables[i]).withProperty(key + "target-table", tables[i])
                    .withProperty(key + "select-sql", "SELECT ID,NAME FROM " + tables[i])
                    .withProperty(key + "insert-sql", "INSERT INTO " + tables[i] + " VALUES (?,?)")
                    .withProperty(key + "primary-key[0]", "ID").withProperty(key + "batch-size", "7")
                    .withProperty(key + "fields[0].source-column", "ID").withProperty(key + "fields[0].target-jdbc-type", "BIGINT")
                    .withProperty(key + "fields[1].source-column", "NAME").withProperty(key + "fields[1].target-jdbc-type", "VARCHAR");
        }
        return env;
    }
    private MigrationRunner.Builder runner(DatabaseFixture db, MockEnvironment env) {
        return MigrationRunner.builder().source(new DriverManagerDataSource(db.sourceUrl, "sa", ""), 8)
                .target(new DriverManagerDataSource(db.targetUrl, "sa", ""), 8).environment(env);
    }

    @Test void infoExplainsMilestonesAndReuseWithoutBatchNoiseOrSpringContext() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 21); db.seed("B", 12);
            try (var migration = runner(db, configuration("A", "B")).build()) {
                events.clear();
                var result = await(migration);
                assertThat(messages()).contains("configuration binding succeeded", "requiredSource=4 declaredSource=8",
                        "Table preflight succeeded", "Table data committed", "Sequence finalization completed", "failedTables=0");
                assertThat(messages()).doesNotContain("Batch started", "Batch committed", "Borrowing host connection", "row-1");
                assertThat(events).allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("runId=" + result.runId()));
                await(migration);
                assertThat(messages()).contains("Migration already succeeded; returning existing result");
            }
        }
    }

    @Test void debugCorrelatesEachWorkerWithItsOwnTaskAndBatch() throws Exception {
        logger.setLevel(Level.DEBUG);
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 250); db.seed("B", 250);
            var result = engine.execute(db.plan(db.task("A"), db.task("B")));
            var batches = events.stream().filter(e -> e.getFormattedMessage().startsWith("Batch committed")).toList();
            assertThat(batches).hasSize(6).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("runId=" + result.runId(), "stage=BATCH_COMMIT", "endpoint=target");
            });
            assertThat(messages()).contains("task=A batch=1", "task=B batch=1").doesNotContain("lower_key", "row-1");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"source", "target"})
    void connectionErrorsIdentifyEndpointAndAuthenticationWithoutDriverMessages(String endpoint) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 3);
            var broken = mock(DataSource.class);
            when(broken.getConnection()).thenThrow(new SQLException("jdbc:oracle:thin:password=" + SECRET, "28P01", 1017));
            var builder = runner(db, configuration("A"));
            if (endpoint.equals("source")) builder.source(broken, 8); else builder.target(broken, 8);
            try (var migration = builder.build()) {
                var failure = catchThrowableOfType(() -> await(migration), MigrationException.class);
                assertThat(failure.getMessage()).contains("A [FAILED", "SQLSTATE=28P01").doesNotContain(SECRET);
                assertThat(messages()).contains("stage=CONNECTION_ACQUIRE endpoint=" + endpoint, "category=AUTHENTICATION_FAILURE");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"source", "target"})
    void transactionInitializationIsDistinctFromAcquisition(String endpoint) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 2);
            var broken = mock(DataSource.class); var raw = mock(Connection.class);
            when(broken.getConnection()).thenReturn(raw);
            when(raw.getAutoCommit()).thenThrow(new SQLException(SECRET, "08006", 99));
            var builder = runner(db, configuration("A"));
            if (endpoint.equals("source")) builder.source(broken, 8); else builder.target(broken, 8);
            try (var migration = builder.build()) {
                assertThatThrownBy(() -> await(migration)).isInstanceOf(MigrationException.class);
                assertThat(messages()).contains("stage=CONNECTION_INITIALIZE endpoint=" + endpoint, "category=CONNECTION_FAILURE");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"batch-size", "unknown-property", "type"})
    void bindingErrorsIdentifyPropertyNamesWithoutRejectedValues(String kind) throws Exception {
        try (var db = new DatabaseFixture()) {
            var env = configuration("A");
            if (kind.equals("batch-size")) env.withProperty("data-migration.tables[0].batch-size", "0");
            else if (kind.equals("type")) env.withProperty("data-migration.tables[0].fields[0].target-jdbc-type", SECRET);
            else env.withProperty("data-migration.strategy.unknown-property", SECRET);
            try (var migration = runner(db, env).build()) {
                assertThatThrownBy(() -> await(migration)).isInstanceOf(RuntimeException.class);
                assertThat(messages()).contains("stage=CONFIG_BIND");
                if (kind.equals("batch-size")) assertThat(messages()).contains("data-migration.tables[0].batch-size", "constraint=Min");
                if (kind.equals("unknown-property")) assertThat(messages()).contains("data-migration.strategy.unknown-property", "UNKNOWN_PROPERTY");
                if (kind.equals("type")) assertThat(messages()).contains("data-migration.tables[0].fields[0].target-jdbc-type");
            }
        }
    }

    @Test void missingConfigurationFileAndInsufficientCapacityFailBeforeBorrowing() {
        var ds = mock(DataSource.class);
        assertThatThrownBy(() -> MigrationRunner.builder().source(ds, 1).target(ds, 1)
                .configLocations(directory.resolve("missing.yml").toUri().toString()).build()).isInstanceOf(RuntimeException.class);
        assertThat(messages()).contains("stage=CONFIG_LOAD", "CONFIGURATION_RESOURCE_NOT_FOUND");
        try (var migration = MigrationRunner.builder().source(ds, 1).target(ds, 1).environment(configuration("A")).build()) {
            assertThatThrownBy(() -> await(migration)).hasMessageContaining("Insufficient pool capacity");
            assertThat(messages()).contains("stage=CAPACITY_CHECK", "requiredSource=2 declaredSource=1");
        }
        verifyNoInteractions(ds);
    }

    @ParameterizedTest @ValueSource(strings = {"source-table", "target-table", "audit-table", "field", "key", "sql"})
    void preflightFailuresIdentifyTheTaskStageAndConfiguration(String kind) throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 4); var env = configuration("A");
            switch (kind) {
                case "source-table" -> env.withProperty("data-migration.tables[0].select-sql", "SELECT ID,NAME FROM NONEXISTENT_SOURCE");
                case "target-table" -> db.target("DROP TABLE A");
                case "audit-table" -> db.target("DROP TABLE migration_audit");
                case "field" -> env.withProperty("data-migration.tables[0].fields[1].source-column", "ABSENT");
                case "key" -> env.withProperty("data-migration.tables[0].select-sql", "SELECT MOD(ID,2) AS ID,NAME FROM A");
                case "sql" -> env.withProperty("data-migration.tables[0].select-sql", "SELECT '" + SECRET + "' FROM");
            }
            try (var migration = runner(db, env).build()) {
                assertThatThrownBy(() -> await(migration)).isInstanceOf(MigrationException.class);
                String expected = switch (kind) {
                    case "source-table" -> "stage=SOURCE_PREFLIGHT endpoint=source";
                    case "target-table" -> "stage=TARGET_PREFLIGHT endpoint=target";
                    case "audit-table" -> "stage=AUDIT_CHECK endpoint=target";
                    case "field" -> "data-migration.tables[0].fields[1].source-column";
                    case "key" -> "Primary-key is not unique in SELECT results";
                    default -> "Cannot parse migration SQL";
                };
                assertThat(messages()).contains("task=A", expected).doesNotContain("SELECT '", "SELECT ID,NAME FROM NONEXISTENT_SOURCE");
                if (kind.equals("audit-table")) assertThat(messages()).contains("original error retained");
            }
        }
    }

    @Test void sequenceFailureReportsFinalizationWithoutLosingCommittedData() throws Exception {
        try (var db = new DatabaseFixture(); var engine = db.engine()) {
            db.seed("A", 15); var original = db.task("A");
            var task = new TableTask(original.name(), original.selectSql(), original.insertSql(), original.fields(),
                    original.targetTable(), original.primaryKey(), 1, original.mode(), new SequenceSpec(true, "ABSENT_SEQ", "ID"), 7);
            var failure = catchThrowableOfType(() -> engine.execute(db.plan(task)), MigrationException.class);
            assertThat(failure.getMessage()).contains("FINALIZATION_FAILED");
            assertThat(messages()).contains("Table data committed", "stage=SEQUENCE_FINALIZE endpoint=target", "Sequence not found");
            assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(15);
        }
    }

    @Test void transientRetryLogsAttemptBackoffAndOriginalEndpoint() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20); var sources = new AtomicInteger();
            ConnectionAccess faulty = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException {
                    if (sources.incrementAndGet() == 2) throw new SQLTransientConnectionException(SECRET, "08006");
                    return db.openSourceConnection();
                }
                public Connection openTargetConnection() throws SQLException { return db.openTargetConnection(); }
            };
            try (var engine = db.engine(faulty)) { engine.execute(db.plan(db.task("A"))); }
            assertThat(events).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Retrying transient batch failure", "attempt=1", "backoffMs=1", "endpoint=source");
            });
        }
    }

    @Test void failedBatchAndChangedSourceKeepPreciseStagesAcrossWorkerThreads() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 20); db.target("ALTER TABLE A ADD CONSTRAINT bad CHECK(ID<>9)");
            try (var migration = runner(db, configuration("A")).build()) {
                assertThatThrownBy(() -> await(migration)).isInstanceOf(MigrationException.class);
                assertThat(messages()).contains("Batch migration failed", "stage=BATCH_WRITE endpoint=target",
                        "property=data-migration.tables[0].insert-sql", "category=CONSTRAINT_VIOLATION");
                assertThat(events).filteredOn(e -> e.getFormattedMessage().startsWith("Table migration failed"))
                        .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("batch=2", "stage=BATCH_WRITE endpoint=target"));
                db.source("DELETE FROM A WHERE ID=1");
                events.clear();
                assertThatThrownBy(() -> await(migration)).isInstanceOf(MigrationException.class);
                assertThat(messages()).contains("stage=SOURCE_COUNT endpoint=source", "reason=Source row count changed");
            }
        }
    }

    @Test void commitUnknownAndFailureReportingOutagePreserveOriginalBatchDiagnostic() throws Exception {
        try (var db = new DatabaseFixture()) {
            db.seed("A", 45);
            var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
            ConnectionAccess faulty = new ConnectionAccess() {
                public Connection openSourceConnection() throws SQLException { return db.openSourceConnection(); }
                public Connection openTargetConnection() throws SQLException {
                    if (unavailable.get()) throw new SQLException(SECRET, "08006");
                    var raw = db.openTargetConnection(); var business = new java.util.concurrent.atomic.AtomicBoolean();
                    return TransactionRecoveryTest.proxy(Connection.class, raw, (method, args) -> {
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO A(")) {
                            var insert = (PreparedStatement) TransactionRecoveryTest.invoke(raw, method, args);
                            return TransactionRecoveryTest.proxy(PreparedStatement.class, insert, (operation, values) -> {
                                if (operation.getName().equals("executeBatch")) business.set(true);
                                return TransactionRecoveryTest.invoke(insert, operation, values);
                            });
                        }
                        if (method.getName().equals("commit") && business.get()) {
                            raw.commit(); unavailable.set(true); throw new SQLException(SECRET, "08006");
                        }
                        return TransactionRecoveryTest.invoke(raw, method, args);
                    });
                }
            };
            try (var engine = db.engine(faulty)) {
                var failure = catchThrowableOfType(() -> engine.execute(db.plan(db.task("A"))), MigrationException.class);
                assertThat(failure.result().tables().get(0).status()).isEqualTo(TableResult.Status.COMMIT_UNKNOWN);
                assertThat(messages()).contains("Commit outcome requires verification", "stage=COMMIT_VERIFY endpoint=target",
                        "Could not persist table failure; original error retained");
                assertThat(events).filteredOn(e -> e.getFormattedMessage().startsWith("Table migration failed"))
                        .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("batch=1", "stage=COMMIT_VERIFY"));
                unavailable.set(false);
                engine.execute(db.plan(db.task("A")));
                assertThat(db.scalar("SELECT COUNT(*) FROM A")).isEqualTo(45);
            }
        }
    }

    @Test void missingSpringDataSourceRoleIsLoggedBeforeOpeningConnections() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        com.datamigration.autoconfigure.MigrationAutoConfiguration.class))
                .withPropertyValues(MigrationAutoConfigurationTest.properties())
                .run(context -> assertThatThrownBy(() -> await(context.getBean(MigrationService.class)))
                        .hasMessageContaining("MigrationSource"));
        assertThat(messages()).contains("stage=DATASOURCE_RESOLVE endpoint=source", "matchingBeans=0");
    }

    @ParameterizedTest @CsvSource({
        "08006,0,CONNECTION_FAILURE", "28P01,0,AUTHENTICATION_FAILURE", "40001,0,TRANSACTION_CONFLICT",
        "22003,0,DATA_ERROR", "23505,0,CONSTRAINT_VIOLATION", "42P01,0,TABLE_NOT_FOUND",
        "42703,0,COLUMN_NOT_FOUND", "42601,0,SQL_SYNTAX_ERROR", "42501,0,PERMISSION_DENIED",
        "0A000,0,UNSUPPORTED_FEATURE", "P0001,0,SKIP_BUDGET_EXCEEDED", "P0004,0,SOURCE_COUNT_CHANGED",
        "42XYZ,0,SQL_OR_SCHEMA_ERROR", "-,0,UNKNOWN", "private,0,UNKNOWN", "72000,17002,CONNECTION_FAILURE"
    })
    void diagnosticCategoriesUseCodesRatherThanDriverMessages(String state, int vendor, String category) {
        try (var scope = MigrationLog.invocation()) {
            MigrationLog.stage("TARGET_PREFLIGHT", "target", "data-migration.tables[0].insert-sql");
            String sqlState = state.equals("-") ? null : state.equals("private") ? SECRET : state;
            MigrationLog.error("Expected database failure", new IllegalStateException(SECRET, new SQLException(SECRET, sqlState, vendor)));
            assertThat(messages()).contains("category=" + category, "endpoint=target");
        }
    }

    @Test void diagnosticsEscapeNamesRetainOnlySafeReasonsAndRestoreThreadContext() throws Exception {
        logger.setLevel(Level.DEBUG);
        assertThat(MigrationLog.safe(null)).isEqualTo("-");
        assertThat(MigrationLog.safe("a\nb\r\t\u2028\u2029")).isEqualTo("a_b____");
        assertThat(MigrationLog.safe("a".repeat(500))).hasSize(300);
        try (var invocation = MigrationLog.invocation()) {
            MigrationLog.job("safe-job"); MigrationLog.taskPath("task\nforged", 3);
            try (var table = MigrationLog.table("safe-job", "task\nforged", 0)) {
                MigrationLog.stage("KEY_PREFLIGHT", "source", "primary-key");
                MigrationLog.error("Expected key failure", new IllegalArgumentException("Missing or ambiguous primary-key column: " + SECRET));
                assertThat(messages()).contains("task=task_forged", "property=data-migration.tables[3].primary-key",
                        "reason=Primary-key column missing or ambiguous");
            }
            MigrationLog.resetFailure();
            MigrationLog.stage("SOURCE_CONNECT", "source", "-");
            MigrationLog.error("Expected timeout", new SQLTimeoutException(SECRET));
            assertThat(messages()).contains("category=TIMEOUT");
            MigrationLog.resetFailure();
            MigrationLog.error("Untrusted validation text", new IllegalArgumentException("Invalid SQL identifier " + SECRET));
            MigrationLog.resetFailure();
            MigrationLog.error("Safe lifecycle message", new IllegalStateException("Migration engine is closed"));
            assertThat(messages()).contains("reason=Migration engine is closed");
            MigrationLog.resetFailure();
            MigrationLog.error("Unknown failure", new Exception(SECRET));
        }
        events.clear();
        MigrationLog.error("Standalone diagnostic", new Exception(SECRET));
        assertThat(messages()).contains("job=- task=-").doesNotContain("safe-job", "task_forged");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "MIGRATION_FAILED", "INVALID_CONFIGURATION_OR_MAPPING",
            "SQLSTATE=unknown; vendor=-1", "SQLSTATE=08006; vendor=9", "private"})
    void publicFailureSummaryIncludesOnlyRecognizedErrorCodes(String code) {
        String error = code.equals("null") ? null : code.equals("private") ? SECRET : code;
        var table = new TableResult("task\nforged", java.time.Instant.now(), java.time.Instant.now(), 1, 0, 0,
                TableResult.Status.FAILED, error);
        var failure = new MigrationException(new MigrationResult(UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now(), List.of(table)));
        assertThat(failure.getMessage()).contains("task_forged", "FAILED").doesNotContain("\n", SECRET);
    }
}
