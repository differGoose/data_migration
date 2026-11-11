# Test directory index

All test sources use `src/test/java`. Surefire discovers `*Test` and Failsafe discovers `*IT`; both line and branch coverage thresholds are 85%. Host DataSource selection, connection-transaction, and Runner tests replace the old credential-provider tests.

| File | Java package | Purpose |
| --- | --- | --- |
| [component/DatabaseDialectTest.java](../src/test/java/com/datamigration/component/DatabaseDialectTest.java) | `com.datamigration.component` | Component contracts. |
| [component/DataSourceConnectionsTest.java](../src/test/java/com/datamigration/component/DataSourceConnectionsTest.java) | `com.datamigration.component` | Connection borrowing/return, rollback failures, and redaction. |
| [component/ExampleConfigurationTest.java](../src/test/java/com/datamigration/component/ExampleConfigurationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ExecutionIsolationTest.java](../src/test/java/com/datamigration/component/ExecutionIsolationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ExecutorScopeTest.java](../src/test/java/com/datamigration/component/ExecutorScopeTest.java) | `com.datamigration.component` | Component contracts. |
| [component/JdbcMigrationEngineTest.java](../src/test/java/com/datamigration/component/JdbcMigrationEngineTest.java) | `com.datamigration.component` | Component contracts. |
| [component/JdbcRowsTest.java](../src/test/java/com/datamigration/component/JdbcRowsTest.java) | `com.datamigration.component` | Component contracts. |
| [component/MigrationAutoConfigurationTest.java](../src/test/java/com/datamigration/component/MigrationAutoConfigurationTest.java) | `com.datamigration.component` | Source/target selection, capacity, and host resource ownership. |
| [component/MigrationRunnerTest.java](../src/test/java/com/datamigration/component/MigrationRunnerTest.java) | `com.datamigration.component` | Pre-startup entry, per-table boundaries, recovery, configuration caching, and lifecycle. |
| [component/MigrationStartupTest.java](../src/test/java/com/datamigration/component/MigrationStartupTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ResilienceBoundaryTest.java](../src/test/java/com/datamigration/component/ResilienceBoundaryTest.java) | `com.datamigration.support` | Accesses package-visible JDBC test decorators. |
| [component/ResilienceConfigurationTest.java](../src/test/java/com/datamigration/component/ResilienceConfigurationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/RowDigestTest.java](../src/test/java/com/datamigration/component/RowDigestTest.java) | `com.datamigration.component` | Component contracts. |
| [component/TcpFaultProxyTest.java](../src/test/java/com/datamigration/component/TcpFaultProxyTest.java) | `com.datamigration.component` | Component contracts. |
| [component/TransactionRecoveryTest.java](../src/test/java/com/datamigration/component/TransactionRecoveryTest.java) | `com.datamigration.component` | Component contracts. |
| [demo/BeforeStartupDemo.java](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java) | `com.datamigration.demo` | Runnable H2 pre-startup example. |
| [demo/H2Demo.java](../src/test/java/com/datamigration/demo/H2Demo.java) | `com.datamigration.demo` | Manual entry point outside test discovery. |
| [e2e/H2MigrationIT.java](../src/test/java/com/datamigration/e2e/H2MigrationIT.java) | `com.datamigration.e2e` | Complete migration and data types. |
| [e2e/H2ScenariosIT.java](../src/test/java/com/datamigration/e2e/H2ScenariosIT.java) | `com.datamigration.e2e` | Complete migration and data types. |
| [resilience/ConfiguredResilienceIT.java](../src/test/java/com/datamigration/resilience/ConfiguredResilienceIT.java) | `com.datamigration.resilience` | Outages, crashes, and recovery. |
| [resilience/DevHarnessContractIT.java](../src/test/java/com/datamigration/resilience/DevHarnessContractIT.java) | `com.datamigration.resilience` | Uses prepared H2 to verify DEV host DataSource extensions, not real database acceptance. |
| [resilience/H2ProcessRecoveryIT.java](../src/test/java/com/datamigration/resilience/H2ProcessRecoveryIT.java) | `com.datamigration.resilience` | Process exit/recovery at batch commit boundaries and after sequence calibration. |
| [resilience/H2VerificationRecoveryIT.java](../src/test/java/com/datamigration/resilience/H2VerificationRecoveryIT.java) | `com.datamigration.resilience` | Source outage before content verification and recovery on the original Runner. |
| [resilience/H2RecoveryIT.java](../src/test/java/com/datamigration/resilience/H2RecoveryIT.java) | `com.datamigration.resilience` | Outages, crashes, and recovery. |
| [resilience/H2ResilienceIT.java](../src/test/java/com/datamigration/resilience/H2ResilienceIT.java) | `com.datamigration.resilience` | Outages, crashes, and recovery. |
| [support/DatabaseFixture.java](../src/test/java/com/datamigration/support/DatabaseFixture.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/H2RecoveryWorker.java](../src/test/java/com/datamigration/support/H2RecoveryWorker.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/HostDataSources.java](../src/test/java/com/datamigration/support/HostDataSources.java) | `com.datamigration.support` | Host-owned Hikari pools for tests. |
| [support/MigrationAssertions.java](../src/test/java/com/datamigration/support/MigrationAssertions.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/ResilienceContexts.java](../src/test/java/com/datamigration/support/ResilienceContexts.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/ResilienceHost.java](../src/test/java/com/datamigration/support/ResilienceHost.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/ResilienceSettings.java](../src/test/java/com/datamigration/support/ResilienceSettings.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/ResilienceSuite.java](../src/test/java/com/datamigration/support/ResilienceSuite.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/ResilienceWorker.java](../src/test/java/com/datamigration/support/ResilienceWorker.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/RowDigest.java](../src/test/java/com/datamigration/support/RowDigest.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/TcpDatabases.java](../src/test/java/com/datamigration/support/TcpDatabases.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [support/TcpFaultProxy.java](../src/test/java/com/datamigration/support/TcpFaultProxy.java) | `com.datamigration.support` | Database, host, proxy, control, and data-verification facilities. |
| [unit/JdbcValueConverterTest.java](../src/test/java/com/datamigration/unit/JdbcValueConverterTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/KeyCodecTest.java](../src/test/java/com/datamigration/unit/KeyCodecTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/SqlErrorsTest.java](../src/test/java/com/datamigration/unit/SqlErrorsTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/SqlSupportTest.java](../src/test/java/com/datamigration/unit/SqlSupportTest.java) | `com.datamigration.sql` | Keeps its package to access the production tokens method. |

Additional external E2E entries and validation fixtures:

- `support/DatabaseE2E`: direct-object, main, and configured entries; ordinary scenarios call the production Runner.
- `support/E2EHost` / `E2ESettings` / `E2EValidation`: external bean selection, scenario configuration, and data/audit validation.
- `e2e/ConfiguredDatabaseE2EIT`: skipped by default; opt in with actual configuration.
- `component/ExternalDatabaseE2ETest`: caller-prepared H2 validates ownership, recovery, new jobs, expected skipped subsets, and timeouts.
- `e2e/ExternalFaultE2EIT`: caller-prepared H2 servers and external configuration validate four fault types, repetition, and keyless reimport recovery.

- [component/GlobalMigrationExecutionTest.java](../src/test/java/com/datamigration/component/GlobalMigrationExecutionTest.java): global exclusion across Spring contexts, cleanup waiting after cancellation, idle Runner shutdown, and minimum budgets.

- [e2e/HostMigrationE2EIT.java](../src/test/java/com/datamigration/e2e/HostMigrationE2EIT.java): simulates host creation of real DataSources and direct MigrationRunner calls using production application.yml. No e2e section or validation SQL; blank connection settings, explicit opt-in, skipped by default.

- [support/ManualMigrationConsole.java](../src/test/java/com/datamigration/support/ManualMigrationConsole.java): local page for independent endpoint cut/restore and explicit run/finish controls.
- [component/ManualMigrationConsoleTest.java](../src/test/java/com/datamigration/component/ManualMigrationConsoleTest.java): control access, execution exclusion, manual recovery, timeouts, and cancellation cleanup.
- [e2e/ManualHostRecoveryIT.java](../src/test/java/com/datamigration/e2e/ManualHostRecoveryIT.java): synthetic databases verify actual source/target TCP disconnects, same-Runner recovery, and repeated execution.

- [component/MigrationLoggingTest.java](../src/test/java/com/datamigration/component/MigrationLoggingTest.java) — Captures INFO/DEBUG/WARN/ERROR to verify configuration/endpoint diagnostics, concurrent batch correlation, unknown commits, secondary audit errors, sequence errors, and secret redaction.

- [component/AsyncMigrationTest.java](../src/test/java/com/datamigration/component/AsyncMigrationTest.java) — Cross-method deduplication, committed progress, recovery, restart, Spring singleton use, commit verification, sequence finalization, keyless transactions, interruption, and shutdown.
- [support/MigrationAwait.java](../src/test/java/com/datamigration/support/MigrationAwait.java) — Waits at test/CLI boundaries while preserving original exception types.

- [component/PoolCapacityTest.java](../src/test/java/com/datamigration/component/PoolCapacityTest.java) — Automatic Hikari limits, concurrency budgets, pre-borrow failure, shared pools, wrappers, rechecks, Spring auto mode, and explicit capacity.

- [component/TableVerificationTest.java](../src/test/java/com/datamigration/component/TableVerificationTest.java) — Per-table sampling, keyed/keyless tables, preserved data/checkpoints on mismatch or query failure, verify-only retry, manual reimport, and sequence ordering.
- [component/TableVerifierTest.java](../src/test/java/com/datamigration/component/TableVerifierTest.java) — Out-of-order data, duplicates, conversion, binary values, LOBs, NULL, and cancellation.
- [unit/DigestFilesTest.java](../src/test/java/com/datamigration/unit/DigestFilesTest.java) — Chunked multi-pass sorting, empty input, and cancellation cleanup. Retains com.datamigration.verification to access package-visible facilities.

- [component/VerificationRunnerTest.java](../src/test/java/com/datamigration/component/VerificationRunnerTest.java) — Public Runner configuration, sync/async reuse during verification, progress, verify-only retry, and manual reimport.
- [e2e/VerificationRecoveryIT.java](../src/test/java/com/datamigration/e2e/VerificationRecoveryIT.java) — Terminates a public Runner JVM during VERIFYING/VERIFICATION_MISMATCH and recovers in a new JVM using synthetic H2 file databases.

- [component/AsyncProgressLoggingTest.java](../src/test/java/com/datamigration/component/AsyncProgressLoggingTest.java) — Invocation snapshots, initially unknown state, final success/failure, retries, and escaped names without a database.
