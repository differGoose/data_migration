# Local implementation and acceptance records

Earlier entries below are historical acceptance evidence. Connection configuration and batching parameters have undergone breaking changes; use README and getting-started.md for current integration. Only JDK 17 is currently supported and verified. All dual-JDK, execution-lock, and fingerprint entries below are historical, not descriptions of current capabilities.

## Simulated host manual proxies and default drivers (2026-10-30)

Add HostMigrationE2EIT, calling the Runner directly with production classpath:application.yml and no e2e fields. Optional manual mode independently controls source/target through TCP proxies and a local run/cut/restore/finish page. After outages, the host evicts old pooled connections before the next explicit attempt without changing engine retry policies. Synthetic-H2 ManualHostRecoveryIT verifies actual source/target disconnects, same-Runner recovery, and repetition. The real entry creates neither H2 nor database objects.

Oracle ojdbc11 and PostgreSQL drivers are default runtime dependencies; resilience-dev is no longer needed. DriverManager discovers both drivers under JDK 17 without attempting real connections.

Full JDK 17 `mvn -B -ntp clean verify` passed: 186 unit/component tests; 22 integration tests, with 19 executed successfully and 3 real entries skipped by default. Unit line coverage was 98.67% and branch coverage 86.07%, retaining both 85% thresholds. Evidence: target-manual-final17.log. Control access, execution exclusion, cancellation cleanup, and explicit recovery were validated. Users supply real Oracle/PostgreSQL connection parameters; no real database integration is claimed. See [manual proxy migration](manual-host-migration.md).

## Single-process coordination and simplified auditing (current design, 2026-10-29)

Default engines share one JVM permit, immediately rejecting overlap across Runners, Spring contexts, and target tables. Cancellation waits for workers and transactions before release. Closing idle Runners cannot release another execution's permit. Database execution locks, guard connections, ownership tokens, fingerprints, and endpoint identity calculations are removed. Batch/summary transactional row locks, commit verification, bounded retries, and same-job recovery remain.

Keyed budgets are source/target W+1 each; keyless budgets are source 1 / target 2. Actually bounded Hikari pools complete at minimum capacity; lower declarations fail early. New audits contain no internal lock rows. See [audit design](audit.md) for fields and consecutive/composite-key examples. Users prepare the schema manually; no old checkpoint conversion is provided.

This round used only Amazon Corretto JDK 17.0.16 for a full successful `mvn -B -ntp clean verify`: 183 unit/component tests; 19 integration tests with 17 executed successfully and 2 real configuration entries skipped by default. Unit lines: 893/905 (98.67%); branches: 618/718 (86.07%), exceeding both original 85% thresholds. Evidence: target-audit-final17.log, target/surefire-reports, target/failsafe-reports, and target/site/jacoco-ut.

External DataSource E2E methods, configuration classes, main/Maven entries, and four fault scenarios remain. Recovery hosts start after faulted hosts exit. Actual E2E main success, repeated jobs, and missing-configuration exit code 1 were verified; BeforeStartupDemo also ran successfully. The ECS host example compiled with release17. All 101 local documentation links and matching PostgreSQL DDL copies passed checks. CI was fixed to JDK 17 with the version matrix removed.

These API/fault checks used caller-prepared synthetic H2 databases without real Oracle/PostgreSQL parameters; real database integration is not claimed. All following entries are historical. Old lock/fingerprint tests have been replaced by current global-exclusion tests.

## External DataSource real-E2E entry (historical, 2026-10-29)

Add DatabaseE2E.run accepting caller DataSources and configuration files. main/ConfiguredDatabaseE2EIT obtain annotated beans from explicit external classes. Tests do not prepare real databases or impose empty targets; the production Runner owns job/mode semantics. External fault routes cover four failures, same-job repetition, and keyless reimport recovery on nonempty targets. Temporary proxy ports normalize identity using actual upstream endpoints; control files do not copy validation SQL.

Both Java 17.0.16 and Java 21.0.2 clean verify runs passed: 187 unit/component tests; 22 integration tests with 20 executed successfully and 2 explicit real entries skipped by default. Unit lines: 98.17%; branches: 85.83%, retaining 85% thresholds. Evidence: target-external-e2e-final17.log and target-external-e2e-final21.log. Actual main success, repetition, and missing-host failure exits were also verified against caller-prepared H2 servers. These are local interface-contract checks, not real Oracle/PostgreSQL integration.

See the [real database E2E guide](real-database-e2e.md) for methods, examples, and Maven commands.

## Host DataSource redesign validation (historical, 2026-10-29)

The host supplies DataSources directly. MigrationRunner executes without creating a Spring context, and the Spring adapter uses source/target annotations. Each table has one batch-size row parameter. Old connection/startup configuration is rejected, and unfinished jobs from earlier versions are not guaranteed compatible.

The final Java 17 clean verify passed: 182 unit/component tests; 16 integration tests with 15 executed successfully and 1 real entry skipped by default; both 85% coverage thresholds passed. Evidence: target-refactor-final17.log. BeforeStartupDemo ran on actual Java 17; ECS host code compiled with release17, and JAR inspection confirmed removed pool/credential classes were absent. Java 21 clean verify also passed with the same test counts (target-refactor-final21.log). Final unit line/branch coverage was 98.17% / 85.83%.

Recovery tests still cover parent TCP proxies, child hosts, four precise business-commit boundaries, and post-recovery data/audit validation. DEV contracts register external DataSources through the test host, without DEV DDL or cleanup. Real Oracle/PostgreSQL/IAM/ECS integration was not performed.

## Earlier validation (historical)

| Requirement | Evidence |
| --- | --- |
| Two H2 databases, 10,000 rows, YAML -> Spring -> method entry | H2MigrationIT |
| TLS migration and untrusted-certificate rejection | H2TlsIT; negative connection check in an actual child JVM |
| Resume after source TCP server interruption | H2RecoveryIT(false) |
| Resume after a target commit whose response is lost | H2RecoveryIT(true) |
| Process exit before/after COMMIT, recovery in a new JVM | H2ProcessRecoveryIT; Runtime.halt(73), final 10,000-row verification |
| Composite primary key and 2MB BLOB | JdbcMigrationEngineTest |
| Preserve original data after keyless reimport failure | JdbcMigrationEngineTest |
| Independent failures across tables | JdbcMigrationEngineTest |
| Concurrent skip budgets, non-regressing sequences, configuration fingerprints | JdbcMigrationEngineTest |
| Execution locks, overlapping calls, successful-audit protection | ExecutionIsolationTest |
| Handoff after lock-connection loss, draining old transactions, rejecting stale owners | TableLockTest; actual H2 row locks and independent connections |
| Type precision, SQL/parameters, key encoding | JdbcValueConverterTest, SqlSupportTest, KeyCodecTest |
| Pre-commit retries, lost post-commit responses, unknown commits, rollback failures | TransactionRecoveryTest |
| No reinsertion after finalization failure, rollback on audit failure, SELECT-only source | TransactionRecoveryTest |
| CLOB/BLOB nulls, size limits, cleanup failures, exact batch row counts | JdbcRowsTest |
| H2 sequence boundaries and PostgreSQL JDBC contracts | DatabaseDialectTest; no real PostgreSQL integration |
| Independent YAML table batches, whole-table rollback on the final keyless row, composite-key concurrency, skip budgets, 3MB BLOB/CLOB, new-job reimport | H2ScenariosIT; two H2 TCP databases and actual Spring service |
| Cancellation on close, rollback waiting, recovery after resource release | ExecutionIsolationTest.closeCancelsInFlightWorkAndWaitsForRollbackBeforeReturning |
| Manually runnable local two-H2 example | H2Demo.startDemoDatabases; 10,000 CUSTOMER rows and a DOCUMENTS LOB example |
| Lost guard before clearing, inside DELETE, or during finalization; safe new-job reimport | Three actual TCP / Spring E2E scenarios in H2OwnershipIT |
| H2 ALTER SEQUENCE retains transactional row locks and cannot be rolled back | DatabaseDialectTest.h2SequenceAlterRetainsExistingTransactionLocksButCannotBeRolledBack |
| Failed tables leave queued batches recoverable while other tables finish | JdbcMigrationEngineTest.failureStopsQueuedBatchesOfThatTableButOtherTablesStillRun |

The 2026-10-28 10:58 `mvn -B -ntp clean verify` passed: **179 unit tests and 10 E2E tests, with zero failures/errors/skips**. Unit lines: **899/917 (98.04%)**; branches: **622/722 (86.15%)**. JaCoCo used Surefire data only, not E2E to reach 85%.

Environment: Amazon Corretto JDK 17.0.16, Maven 3.9.6, Spring Boot BOM 4.1.1, JUnit 5.13.4, H2 2.4.240. Original evidence: jdk17-verify.log, target/surefire-reports, target/failsafe-reports, target/site/jacoco-ut/index.html. Artifact: target/data_migration-0.1.0-SNAPSHOT.jar; javap confirmed class major version 61 (Java 17).

Java 17 adaptation replaced List.getFirst, Duration.isPositive, and ExecutorService try-with-resources. ExecutorScopeTest added normal waiting and interrupted cleanup checks. Existing close/rollback, process recovery, and TLS tests all ran on actual JDK 17. CI at the time used a Java 17 / 21 matrix; cloud results depend on GitHub Actions.

## Earlier delivery checklist (historical)

Additional resilience/directory acceptance on 2026-10-29: JDK 21.0.2 `mvn -B -ntp clean verify` passed, with 190 unit/component tests and 16 integration tests (15 passed, 1 real entry skipped by default). Unit coverage was 98.03% lines / 86.42% branches, retaining 85% thresholds. All four H2 fault scenarios triggered on the configured task's second business commit, then verified recovery and repetition. Local DEV contracts covered credential extensions and nonempty-target rejection. ConfiguredResilienceIT also passed separately with file lists and explicit SOURCE_OUTAGE. Evidence: target-resilience-completion-verify.log, target-resilience-entry-verify.log, target/resilience-reports. Real Oracle/PostgreSQL acceptance still requires the actual environment.

See the [test index](test-index.md) for relocated packages, fixtures, and entries, and the [resilience guide](resilience-testing.md) for environment examples and execution.

| Deliverable | Evidence at that time |
| --- | --- |
| Single Maven module, regular JAR, no REST, method always available | pom.xml packaging=jar; JAR contains MigrationService and AutoConfiguration.imports, not BOOT-INF or host application.yml; autoconfiguration tests confirm no default pool creation and an injectable method |
| YAML endpoint connections/pools/strategies/tables | MigrationProperties, MigrationConfiguration, examples/migration.yml; binding and invalid-configuration tests |
| Nonintrusive host integration and extensions | MigrationAutoConfigurationTest preserves host DataSources/transaction managers and supports custom engines/services; README describes supported extension points |
| Durable single/composite-key recovery and keyless whole-table transactions | Engine component tests, H2MigrationIT, H2RecoveryIT, H2ProcessRecoveryIT, H2ScenariosIT |
| Two-level concurrency, failure isolation, connection budgets, shutdown lifecycle | Fixed table/worker pools and budget preflight; ExecutionIsolationTest, JdbcMigrationEngineTest, H2ScenariosIT |
| Atomic audit/data transactions and no blind replay of unknown commits | TransactionRecoveryTest, H2RecoveryIT, H2ProcessRecoveryIT; engine executes no DDL |
| Reimport and sequence finalization | H2ScenariosIT, H2OwnershipIT, JdbcMigrationEngineTest, TransactionRecoveryTest, DatabaseDialectTest |
| LOBs and NULL | 2MB composite-key BLOB component tests, 3MB BLOB/CLOB E2E, JdbcRowsTest resource cleanup/limits/NULL |
| Two H2 databases, 10,000 rows, manual startup, TLS | H2Demo, TcpDatabases; trusted-certificate migration and untrusted-certificate child rejection in H2MigrationIT/H2TlsIT |
| Documentation, DDL, ECS integration | README, architecture.md, cloud-deployment.md, examples/ecs; matching SHA256 for PostgreSQL DDL in docs/sql and resources |
| Unit coverage >=85% and passing E2E | clean verify and JaCoCo reports above |

SQL preflights columns, parameters, and unique keys before clearing. Static source data and paused target writes are prerequisites. Sequence failures occur during finalization after data commits; recover with the same job-id without claiming the entire migration rolled back. H2 PostgreSQL-dialect unit contracts do not establish real PostgreSQL integration.

## Explicitly excluded from local acceptance claims

Real Oracle/PostgreSQL environments, Oracle TCPS, AWS IAM, and ECS deployment.
These are cloud integration tasks the user agreed to defer; integration/deployment instructions must still be provided.
