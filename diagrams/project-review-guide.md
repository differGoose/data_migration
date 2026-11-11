# Project walkthrough and code review guide

Use the [project flowchart](project-overview.svg) to introduce the project. This guide follows one execution and identifies implementation details for a team review.

## 1. Introduce the project

The component accepts caller-owned source and target DataSources and YAML migration rules. It maps source query results into target tables, supports concurrent tables and keyed batches, and persists checkpoints in the target database. Later calls use auditing to skip completed tables or resume unfinished work. Optional per-table verification and sequence calibration follow data transfer.

Walk through entry, auditing, transfer, verification, and finalization in the diagram. Configuration, endpoints, and the source range must remain stable during recovery. Only one migration process may run at a time: a JVM permit coordinates local engines, while deployment prevents cross-process overlap.

## 2. Entry points and lifecycle

Keep one Runner or singleton MigrationService. Both use AsyncMigration and MigrationSession.

| Situation | migrate() | migrateAsync() |
|---|---|---|
| No execution running | Start and wait; throw on failure | Start and immediately return MigrationExecution |
| Execution running | Immediately return the current execution | Immediately return the current execution |
| Previous execution finished | Start a new attempt and re-read audit | Start a new attempt and re-read audit |

MigrationExecution exposes progress() from memory and completion() for the result/error. runId identifies an attempt; job-id and task name identify persistent recovery records. build() loads the environment. The first execution binds/validates configuration, which later attempts reuse. Closing a Runner cleans up execution resources without closing caller DataSources.

Review synchronization around checking/creating attempts, creation after success or failure, and waiting outside the synchronized block so duplicate calls return promptly.

Read [MigrationRunner](../src/main/java/com/datamigration/api/MigrationRunner.java), [AsyncMigration](../src/main/java/com/datamigration/execution/AsyncMigration.java) (start/startSync/run), [MigrationMonitor](../src/main/java/com/datamigration/execution/MigrationMonitor.java) (reusable/completion), and [MigrationConfiguration](../src/main/java/com/datamigration/properties/MigrationConfiguration.java).

## 3. Concurrency and capacity

Configuration and capacity checks precede the engine's JVM-wide single-permit Semaphore. A competing Runner fails immediately; same-Runner calls reuse its running execution. max-concurrent-tables bounds table tasks and worker-count bounds keyed workers. Cleanup finishes before permit release.

| Table | Source connections | Target connections |
|---|---|---|
| Keyed, W workers | W + 1 | W + 1 |
| Keyless, one worker | 1 | 2 |

For each endpoint, sum the largest demands up to max-concurrent-tables. HikariCP maximumPoolSize is detected automatically. A recognized shared pool must satisfy combined endpoint demand; the caller reserves business headroom.

Review concurrency limits, coordinator connections, keyless source-coordinator release before worker borrowing, and worker termination before permit release on errors/interruption/close.

Read [MigrationSession](../src/main/java/com/datamigration/execution/MigrationSession.java), [ConnectionBudget](../src/main/java/com/datamigration/execution/ConnectionBudget.java), [PoolCapacity](../src/main/java/com/datamigration/jdbc/PoolCapacity.java), and [JdbcMigrationEngine](../src/main/java/com/datamigration/execution/JdbcMigrationEngine.java) (executeScoped/keyed/close).

## 4. Audit decisions and recovery

The audit key is (job_id, task_name, batch_no). Zero is the table summary; positive numbers are transaction blocks. Target auditing is checked before borrowing a source connection.

| Audit state | Action |
|---|---|
| SUCCEEDED summary | Return existing result; skip source, verification, and sequence |
| No summary or batches | Start from scratch |
| Data incomplete | Resume pending batches and plan remaining ranges |
| data_complete=true, not successful | Continue verification/finalization |
| Batches without summary | AUDIT_SUMMARY_MISSING; manual investigation |
| Legacy CLEANUP_PENDING / VERIFICATION_FAILED | LEGACY_VERIFICATION_RESET_REQUIRED; manual handling |

lower_key and upper_key describe (lower_key, upper_key], including composite keys. A null first lower boundary means the query range start. Parallel batches can complete out of order, so recovery checks all pending batches, not just the largest completed key.

For manual reimport, stop migration, clear intended target data and ALL audit records for that job/task, then call the original Runner. Clearing business data alone leaves successful auditing that skips the table.

Review source-free successful skips, pending-state coverage, and explicit failure for incomplete audit. Read [CheckpointStore](../src/main/java/com/datamigration/audit/CheckpointStore.java) (find/pending/last) and JdbcMigrationEngine.tableScoped().

## 5. Planning and transactions

Preflight validates source fields, keys, and target INSERT parameters. Prepared-task recovery checks source counts, relying on stable source data.

Keyed tables execute pending batches first. When no pending work remains and planning is unfinished, planWindow() plans at most 2 × worker-count blocks of at most batch-size rows. It commits range auditing before worker execution and repeats until complete.

Workers read their key ranges and convert mapped fields with JdbcRows/ValueConverter. The SAME target transaction writes business rows and calls CheckpointStore.complete() to update batch state, summary counts, and skip allowance.

| Setting | Effect |
|---|---|
| batch-size | Keyed transaction size, fetch hint, JDBC batch row limit |
| max-batch-bytes | Execute JDBC batch earlier, without moving commit boundary |
| max-lob-size | Limit an individual LOB; streaming LOBs use their write path |
| Keyless table | One worker, one whole-table transaction; batch-size controls fetch/JDBC batch |

APPEND_RESUME appends and recovers through auditing. CLEAR_AND_REIMPORT clears at preparation. Keyed tables commit clearing with prepared and do not clear again during recovery. Keyless tables include clearing, writes, and successful checkpoint in the whole-table transaction, rolling everything back on failure.

Review shared data/checkpoint connection, batch row locks, summary locks for counters/skip quota, executeBatch versus commit, tail batches, composite keys, LOBs, and conversion failures.

Read JdbcMigrationEngine.preflight/planWindow/transferScoped, [JdbcRows](../src/main/java/com/datamigration/jdbc/JdbcRows.java), CheckpointStore.completeScoped, and [SqlSupport](../src/main/java/com/datamigration/sql/SqlSupport.java).

## 6. Failure, retry, and commit uncertainty

Pre-commit failures attempt rollback. Confirmed rollback plus an eligible transient classification permits bounded retries with configured backoff. Keyed tasks supporting skipped rows can isolate data errors using savepoints.

After commit() throws, a new target connection locks and reads the batch audit. Success confirms commit; otherwise the batch is treated as uncommitted. If that check also fails, this attempt reports COMMIT_UNKNOWN and a later explicit call resolves recovery.

Table failures attempt to persist safe error codes/states. A second failure while writing audit is logged separately, preserving the original error. Other tables can finish independently; MigrationException aggregates failed tables.

Review confirmed transaction outcomes before retry, locked commit verification waiting for the original transaction, and original-error preservation. Read transferScoped exception handling, [SqlErrors](../src/main/java/com/datamigration/execution/SqlErrors.java), and [MigrationException](../src/main/java/com/datamigration/api/MigrationException.java).

## 7. Verification and sequence finalization

Data completion records data_complete=true. Enabled verification enters VERIFYING. Count checks query the source SELECT and entire target table; both must equal audited committed_rows. Verification requires skip-limit=0. Existing target rows affect this comparison.

Sample size is ceil(source count × sample-percent / 100). Both endpoints' mapped fields are scanned. Source values are converted to target types, normalized, hashed, and externally sorted in temporary files. An exact random source sample selects digests whose multiplicities are compared across endpoints. Lower percentages reduce compared content, but scanning/hashing still cover all rows.

Mismatches record VERIFICATION_MISMATCH; query and other errors record VERIFICATION_ERROR. Data and successful checkpoints remain; subsequent attempts resume verification.

Sequence calibration reads MAX from the TARGET table/column and checks target sequence state, advancing only when needed. PostgreSQL setval puts the next value above migrated IDs. Supported sequences have increment 1 and no cycle. An empty target column needs no calibration. Finalization failures record FINALIZATION_FAILED; the next attempt repeats configured verification/finalization. Success commits SUCCEEDED to the summary.

Review verification scope, numeric/time/NULL/LOB normalization, sampling semantics, full-scan/temp-disk costs, and forward-only sequence advancement. Read [TableVerifier](../src/main/java/com/datamigration/verification/TableVerifier.java), [DigestFiles](../src/main/java/com/datamigration/verification/DigestFiles.java), and [DatabaseDialect](../src/main/java/com/datamigration/dialect/DatabaseDialect.java).

## 8. Team sharing and review notes

Use five minutes for the flowchart and 15–20 minutes for sections 2–7. Explain intended behavior before implementation conditions and failure branches. Select supporting tests from the [test index](../docs/test-index.md): mixed calls, rollback/commit uncertainty, subprocess/network recovery, verification retries, and sequence recovery.

| Topic | Confirm | Conclusion/action |
|---|---|---|
| Entry/lifecycle | Running reuse, audit re-read, caller-owned pools | |
| Concurrency | Bounded tasks and coordinator capacity | |
| Transactions | Atomic data/checkpoints, verifiable commit uncertainty | |
| Recovery | Recover out-of-order batch gaps | |
| Verification | Scope, semantics, sampling, scan cost | |
| Finalization | Forward-only sequence and retryable finalization | |
