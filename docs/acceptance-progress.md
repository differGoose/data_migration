# Local implementation and acceptance records

## English branch synchronization (2026-11-12)

Merged main at 5fd7747 into codex/english-docs-comments without changing main. Updated English integration, configuration, verification, recovery, logging, test index, historical evidence, and sharing materials, including a rendered English SVG. Runtime logic matches main; English console text and explanatory comments remain branch-specific. Intentional Unicode test data is preserved.

Amazon Corretto 17.0.16 `mvn -B -ntp clean verify` passed: 296 unit/component tests and 22 integration tests passed, with three real-database entries skipped by default. UT coverage: lines 1674/1696 (98.70%), branches 1050/1194 (87.94%); both 85% gates passed. HostMigration and MigrationUtil compiled with `javac --release 17`. All 174 local documentation links passed and the English SVG was rendered and visually inspected. No real database connections were attempted. Local ignored build log: target-english-sync-jdk17.log.

This page preserves dated historical evidence. Automatic cleanup on verification mismatch and CLEANUP_PENDING recovery have since been removed. Current verification preserves data and checkpoints and retries verification explicitly; see [table verification](table-verification.md). Historical counts and behavior do not describe the current release.

## Re-read auditing after each execution (2026-11-04)

Synchronous and asynchronous calls share an execution only while it is unfinished. A new attempt reads target auditing to skip, resume, or restart each table. Successful tables do not borrow a source connection. Orphaned batches without a summary fail with AUDIT_SUMMARY_MISSING.

JDK 17 mvn -B -ntp clean verify passed: 296 unit/component tests and 20 integration tests passed; three real-database entries were skipped by default. Line coverage was 98.70%, branch coverage 87.94%; both 85% gates passed.

## Preserve verification mismatches and retry verification only (2026-11-03)

Removed verification-triggered business DELETE, checkpoint deletion, and counter resets. VERIFICATION_MISMATCH preserves data_complete=true and successful batches. Explicit retries skip data transfer and INSERT preflight, verify again, then continue sequence finalization. Legacy CLEANUP_PENDING / VERIFICATION_FAILED stop with LEGACY_VERIFICATION_RESET_REQUIRED for manual handling. Audit DDL did not change.

JDK 17 clean verify passed with 293 unit/component tests and 20 of 23 integration tests passing; three real entries were skipped. Separate JVMs restarted after VERIFYING and VERIFICATION_MISMATCH, retained incorrect data, and verified successfully after manual correction. A further test covered manual deletion of target data and all task auditing, followed by a new Runner. Subsequent -DskipITs verify passed all 294 unit/component tests. Final coverage: lines 98.70%, branches 87.77%. All 150 local documentation links passed. No real databases, commits, or pushes were involved in this recorded validation.

## Pre-main checks (2026-11-03, historical cleanup behavior)

JDK 17 clean verify passed: 290 unit/component tests, 20 integration tests, three real entries skipped. Review found that in-memory progress was not reset after pending cleanup recovery. The fix added intermediate-count assertions. A subsequent -Dit.test=VerificationRecoveryIT verify passed 290 unit/component tests and two separate-JVM verification recovery tests. Coverage: lines 98.72%, branches 87.73%. Review had no remaining findings. The development version was retained; no real database was used and the English branch was unchanged.

## Progress logging on invocation (2026-11-03)

Every migrate/migrateAsync invocation logs an INFO memory snapshot and per-table committed counts; background completion logs final status. Initial synchronous waiting and running-call deduplication remain unchanged. JDK 17 -DskipITs verify passed 290 unit/component tests; coverage was 98.72% lines and 87.65% branches. All 64 targeted asynchronous/logging/verification regressions and 149 local links passed. Integration tests were not rerun in this step; earlier full evidence is below. No real databases, commits, or pushes were involved.

## Table verification and failure cleanup (2026-11-03, historical: cleanup removed)

- JDK 17 clean verify passed: 288 unit/component tests, 20 integration tests, three real entries skipped. Local ignored log: target-table-verification-jdk17.log.
- JaCoCo UT coverage: lines 1680/1703 (98.65%), branches 1034/1182 (87.48%); both 85% gates passed.
- Covered per-table percentages, exact rounding, empty tables, composite/keyless tables, minimum capacity, duplicate rows, conversion, host converters, LOBs, safe logs, and mixed synchronous/asynchronous deduplication.
- Historical tests covered whole-table cleanup on confirmed mismatch, atomic cleanup/checkpoint reset, cleanup-intent persistence failure, lost cleanup commit response, query timeouts preserving data, and sequence retries. Separate JVMs exited after VERIFYING/CLEANUP_PENDING and recovered through the public Runner.
- Host examples compiled with Java 17. External DataSource templates used temporary host placeholder types under target. All 147 local README/docs links passed; audit DDL was unchanged.
- Existing uncommitted automatic-capacity and host-utility changes were preserved. No real Oracle/PostgreSQL connections, automatic commits/pushes, or English-branch edits occurred in this recorded step.

H2TlsIT, certificate subprocesses, and ECS-specific examples have been removed. Their names below describe historical evidence only. See [host DataSource responsibilities](database-credentials.md). Earlier connection/batch APIs, dual-JDK checks, execution locks, and fingerprints are historical; current support and validation target JDK 17 only.

## Automatic HikariCP capacity checks (2026-11-01)

Added source(DataSource)/target(DataSource); MigrationUtil no longer requires SOURCE_CAPACITY/TARGET_CAPACITY. Annotation capacity defaults to zero for automatic detection. Explicit positive capacities remain for custom/unpooled sources and reserved shared-pool quotas. Budgets use enabled tables, max-concurrent-tables, and worker-count, checking actual HikariCP maximumPoolSize. Spring DelegatingDataSource chains and JDBC unwrap are supported. Recognized shared pools require the combined budget. Host pools are not modified; no production Hikari dependency was added.

Insufficient capacity reports endpoint, required capacity, and actual maximumPoolSize before borrowing. Initial synchronous calls throw; asynchronous callers inspect completion and safe logs. Unknown maxima fail explicitly; declarations cannot hide smaller detected pools. Explicit retry re-reads pool limits. Successful-execution caching was retained at that historical date and has since been removed. Static capacity checks do not guarantee free business-pool connections; the host reserves headroom.

Amazon Corretto 17.0.16 clean verify passed: 258 unit/component tests, 18 integration tests, three real entries skipped. UT lines 1430/1447 (98.83%), branches 836/950 (88.00%). Seventeen capacity tests covered budgets, minimum pools, failure without borrowing, shared pools, wrappers, unknown types, pool adjustment/recovery, keyless budgets, Spring auto-selection, and safe logs. Log: target-pool-final17.log.

HostMigration/MigrationUtil compiled with release17. A separate-JVM utility smoke test covered automatic capacity, undersized-pool failure, corrected retries, mixed-call reuse, and host ownership (target-pool-host-smoke17.log). BeforeStartupDemo passed (target-pool-demo17.log). No real database, commit, push, or English-branch change occurred.

## TLS fixtures and legacy integration cleanup (2026-11-01)

Removed H2TlsIT, the untrusted-certificate subprocess, certificate generation, and SSL switches; H2 fixtures use plain TCP. Removed unused public types AuditRepository, TableTaskExecutor, TableReader, BatchWriter, and RowCursor, unused host-databases driver/pool settings, and 22 unused/duplicate imports.

examples/resilience/h2.yml is the sole H2 recovery configuration, still exposed at classpath:resilience/h2.yml through Maven test resources. DEV templates reference host DataSource configuration and retain parent proxies, traffic checks, and separate JVM recovery. ECS-specific entry/Dockerfile were removed; authentication, TLS, pools, and deployment belong to the host. Drivers, active extensions, and pending sync/async/progress work were preserved.

Corretto 17.0.16 clean verify passed: 241 unit/component tests, 18 integration tests, three real entries skipped. UT lines 1363/1379 (98.84%), branches 774/882 (87.76%). Log: target-cleanup-verified17.log. HostMigration compiled with release17; BeforeStartupDemo passed with then-current successful-handle reuse (target-cleanup-demo17.log).

The JAR excluded the five removed types and retained execution/progress APIs. Packaged H2 configuration matched the examples source byte-for-byte. Current links passed; removed TLS/ECS evidence was labeled historical. No real databases, commits/pushes, or English-branch changes occurred.

## Unified synchronous/asynchronous deduplication and progress (2026-11-01, historical terminal reuse)

Added migrateAsync() alongside migrate(), both returning MigrationExecution. Initial synchronous calls waited and propagated errors. At this historical date, running OR successful attempts reused the same object; current behavior only reuses running attempts. Failed attempts could be explicitly retried after cleanup with the same job/config and auditing. Different Runner instances remained protected by the JVM-wide permit.

progress() exposes immutable run/table snapshots, totals, confirmed commits, and skipped counts without polling databases or changing budgets. Source totals are initially unknown. Keyless uncommitted rows are not shown as complete. Data completion and sequence finalization are distinct. completion() preserves MigrationResult/errors; cancelling a derived future does not cancel migration. close waits for worker/transaction cleanup and leaves host DataSources open. Progress keeps table-level counters; active-batch flags prevent duplicate accounting during commit verification.

Corretto 17.0.16 clean verify passed: 241 unit/component tests, 19 integration tests, three real entries skipped. UT lines 1363/1379 (98.84%), branches 774/882 (87.76%). Eleven new tests covered sync-first/async-first, mixed concurrent calls, Spring singleton, immutable progress, historical success caching, recovery/new Runner, keyless transactions, sequence failure, interruption/close, commit verification, and connection-return failure without double counting. Log: target-async-verified17.log.

HostMigration and the then-present ECS example compiled with release17. BeforeStartupDemo ran in a separate JVM (target-async-startup17.log). Parent-proxy/subprocess recovery passed. No real Oracle/PostgreSQL connection was attempted. See [host execution](host-execution.md). This historical step ran on main without commit/push or English-branch changes.

## Key-stage logging and safe failure diagnostics (2026-10-30)


Add com.datamigration SLF4J diagnostics: INFO for configuration, endpoint connections, budgets, table preflight, recovery/reuse, data commits, and sequence finalization; DEBUG for batches; WARN for bounded retries; ERROR for the original failure phase. Correlate runId/task/batch across threads. Keep only safe categories, property paths, SQLState, and vendor codes, without sending driver messages or exception stacks to the logger. MigrationException messages include failed tasks, statuses, and existing safe error codes. Audit structure and migration/retry transaction rules are unchanged.

Full JDK 17 verification (Amazon Corretto 17.0.16), `mvn -B -ntp clean verify`, passed: 230 unit/component tests; 22 integration tests, with 19 passing and 3 real-database entries skipped by default. UT line coverage is 1221/1239 (98.55%); branch coverage is 720/822 (87.59%), retaining both original 85% gates. The 44 new logging cases cover missing/unknown configuration, budgets, both connection endpoints and transaction initialization, audit/business tables and fields, keys, concurrent batch failure, commit uncertainty, secondary audit failure, sequence finalization, and synthetic-secret redaction. Full verification log: target-logging-final17.log.

Run BeforeStartupDemo in a standalone JDK 17 process: migration completion is logged before Spring Boot startup, repeated successful tasks reuse auditing, and the host then starts successfully. Log: target-logging-startup17.log. All 117 local documentation links passed. The host controls pre-startup logging; do not rely on Boot logging.level before Boot initializes it. See [logging and troubleshooting](logging-and-troubleshooting.md). No real Oracle/PostgreSQL connections were attempted. Existing sequence example comments are retained. These verification records were collected before commit/push.

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
