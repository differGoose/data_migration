# Test directory index

All test sources use `src/test/java`. Surefire discovers `*Test` and Failsafe discovers `*IT`; both line and branch coverage thresholds are 85%. Host DataSource selection, connection-transaction, and Runner tests replace the old credential-provider tests.

| File | Java package | Purpose |
| --- | --- | --- |
| [component/DatabaseDialectTest.java](../src/test/java/com/datamigration/component/DatabaseDialectTest.java) | `com.datamigration.component` | Component contracts. |
| [component/DataSourceConnectionsTest.java](../src/test/java/com/datamigration/component/DataSourceConnectionsTest.java) | `com.datamigration.component` | Connection borrowing/return, rollback failure, and error redaction. |
| [component/ExampleConfigurationTest.java](../src/test/java/com/datamigration/component/ExampleConfigurationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ExecutionIsolationTest.java](../src/test/java/com/datamigration/component/ExecutionIsolationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ExecutorScopeTest.java](../src/test/java/com/datamigration/component/ExecutorScopeTest.java) | `com.datamigration.component` | Component contracts. |
| [component/JdbcMigrationEngineTest.java](../src/test/java/com/datamigration/component/JdbcMigrationEngineTest.java) | `com.datamigration.component` | Component contracts. |
| [component/JdbcRowsTest.java](../src/test/java/com/datamigration/component/JdbcRowsTest.java) | `com.datamigration.component` | Component contracts. |
| [component/MigrationAutoConfigurationTest.java](../src/test/java/com/datamigration/component/MigrationAutoConfigurationTest.java) | `com.datamigration.component` | Source/target annotation selection, capacity declarations, and host resource isolation. |
| [component/MigrationRunnerTest.java](../src/test/java/com/datamigration/component/MigrationRunnerTest.java) | `com.datamigration.component` | Pre-startup entry, per-table boundaries, retries/resume, configuration caching, and lifecycle. |
| [component/MigrationStartupTest.java](../src/test/java/com/datamigration/component/MigrationStartupTest.java) | `com.datamigration.component` | Component contracts. |
| [component/ResilienceBoundaryTest.java](../src/test/java/com/datamigration/component/ResilienceBoundaryTest.java) | `com.datamigration.support` | Package access to the test fixture JDBC decorator. |
| [component/ResilienceConfigurationTest.java](../src/test/java/com/datamigration/component/ResilienceConfigurationTest.java) | `com.datamigration.component` | Component contracts. |
| [component/RowDigestTest.java](../src/test/java/com/datamigration/component/RowDigestTest.java) | `com.datamigration.component` | Component contracts. |
| [component/TcpFaultProxyTest.java](../src/test/java/com/datamigration/component/TcpFaultProxyTest.java) | `com.datamigration.component` | Component contracts. |
| [component/TransactionRecoveryTest.java](../src/test/java/com/datamigration/component/TransactionRecoveryTest.java) | `com.datamigration.component` | Component contracts. |
| [demo/BeforeStartupDemo.java](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java) | `com.datamigration.demo` | Runnable H2 pre-startup migration example. |
| [demo/H2Demo.java](../src/test/java/com/datamigration/demo/H2Demo.java) | `com.datamigration.demo` | Manual entry, excluded from test discovery. |
| [e2e/H2MigrationIT.java](../src/test/java/com/datamigration/e2e/H2MigrationIT.java) | `com.datamigration.e2e` | Full migration, types, and TLS. |
| [e2e/H2ScenariosIT.java](../src/test/java/com/datamigration/e2e/H2ScenariosIT.java) | `com.datamigration.e2e` | Full migration, types, and TLS. |
| [e2e/H2TlsIT.java](../src/test/java/com/datamigration/e2e/H2TlsIT.java) | `com.datamigration.e2e` | Full migration, types, and TLS. |
| [resilience/ConfiguredResilienceIT.java](../src/test/java/com/datamigration/resilience/ConfiguredResilienceIT.java) | `com.datamigration.resilience` | Network outages, crashes, and recovery. |
| [resilience/DevHarnessContractIT.java](../src/test/java/com/datamigration/resilience/DevHarnessContractIT.java) | `com.datamigration.resilience` | Caller-prepared H2 verifies DEV host DataSource extensions, not real database acceptance. |
| [resilience/H2ProcessRecoveryIT.java](../src/test/java/com/datamigration/resilience/H2ProcessRecoveryIT.java) | `com.datamigration.resilience` | Network outages, crashes, and recovery. |
| [resilience/H2RecoveryIT.java](../src/test/java/com/datamigration/resilience/H2RecoveryIT.java) | `com.datamigration.resilience` | Network outages, crashes, and recovery. |
| [resilience/H2ResilienceIT.java](../src/test/java/com/datamigration/resilience/H2ResilienceIT.java) | `com.datamigration.resilience` | Network outages, crashes, and recovery. |
| [support/DatabaseFixture.java](../src/test/java/com/datamigration/support/DatabaseFixture.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/H2RecoveryWorker.java](../src/test/java/com/datamigration/support/H2RecoveryWorker.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/HostDataSources.java](../src/test/java/com/datamigration/support/HostDataSources.java) | `com.datamigration.support` | Test host creates its Hikari pools; not component connection configuration. |
| [support/MigrationAssertions.java](../src/test/java/com/datamigration/support/MigrationAssertions.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/ResilienceContexts.java](../src/test/java/com/datamigration/support/ResilienceContexts.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/ResilienceHost.java](../src/test/java/com/datamigration/support/ResilienceHost.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/ResilienceSettings.java](../src/test/java/com/datamigration/support/ResilienceSettings.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/ResilienceSuite.java](../src/test/java/com/datamigration/support/ResilienceSuite.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/ResilienceWorker.java](../src/test/java/com/datamigration/support/ResilienceWorker.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/RowDigest.java](../src/test/java/com/datamigration/support/RowDigest.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/TcpDatabases.java](../src/test/java/com/datamigration/support/TcpDatabases.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/TcpFaultProxy.java](../src/test/java/com/datamigration/support/TcpFaultProxy.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [support/UntrustedTlsClient.java](../src/test/java/com/datamigration/support/UntrustedTlsClient.java) | `com.datamigration.support` | Database, host, proxy, control, and validation fixtures. |
| [unit/JdbcValueConverterTest.java](../src/test/java/com/datamigration/unit/JdbcValueConverterTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/KeyCodecTest.java](../src/test/java/com/datamigration/unit/KeyCodecTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/SqlErrorsTest.java](../src/test/java/com/datamigration/unit/SqlErrorsTest.java) | `com.datamigration.unit` | Independent logic. |
| [unit/SqlSupportTest.java](../src/test/java/com/datamigration/unit/SqlSupportTest.java) | `com.datamigration.sql` | Retains the original package to access production package-private tokens. |

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

- [component/MigrationLoggingTest.java](../src/test/java/com/datamigration/component/MigrationLoggingTest.java): capture INFO/DEBUG/WARN/ERROR and verify configuration/endpoint diagnosis, concurrent batch attribution, original failure retention during commit uncertainty and secondary audit failures, sequence failure, and secret redaction.
