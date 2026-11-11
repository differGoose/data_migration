# Changelog

## Unreleased

- Calls after an attempt finishes create a new MigrationExecution and read target auditing to skip successful tables or resume unfinished tasks. The original Runner can reimport after manual removal of target data and all task audit rows. Orphan batch rows report AUDIT_SUMMARY_MISSING.
- Verification mismatches preserve data and checkpoints under VERIFICATION_MISMATCH. Explicit retries repeat verification. Automatic deletion and checkpoint reset have been removed. Legacy cleanup states require manual reset.
- Every migrate()/migrateAsync() invocation logs an INFO progress snapshot and per-table committed counts. Background completion logs final status. Unknown configuration/totals are identified explicitly, using memory snapshots without borrowing connections.
- Optional per-table count verification and exact-size random sampling support keyless tables and duplicate rows. Chunked digest sorting bounds memory. Mismatches and query failures preserve state using existing audit fields. Verification precedes sequence finalization.
- DataSource entry points automatically read HikariCP maximumPoolSize and validate the concurrency budget, including combined demand for a shared pool. Explicit retries re-read capacity. Optional explicit capacity remains available for custom sources and migration allowances within shared pools.
- Removed H2 TLS tests, certificate subprocesses, SSL switches, and the unused public AuditRepository, TableTaskExecutor, TableReader, BatchWriter, and RowCursor interfaces.
- Removed unused test driver/pool settings and duplicate H2 recovery resources. DEV recovery uses a host DataSource configuration class. Authentication, TLS, pools, and deployment are host responsibilities. Generic host examples replace ECS-specific code and Dockerfile.
- Synchronous migrate() and asynchronous migrateAsync() share execution state. Running duplicates return the same MigrationExecution. Subsequent calls after completion read auditing again. progress() returns immutable committed progress and completion() exposes the result or exception. The public return type changed from MigrationResult to MigrationExecution. Closing waits for cleanup and preserves host DataSource ownership.
- Added configuration, DataSource, capacity, preflight, data commit, and sequence logs. Batch detail uses DEBUG, bounded retries WARN, and terminal failures ERROR. Cross-thread run/task/batch correlation retains the first safe failure stage and SQLState/vendor code. MigrationException includes safe task summaries. See the logging guide.

- Add an optional manual proxy mode to the real host migration test. A local control page independently cuts/restores source and target connections and explicitly resumes the original job with unchanged configuration; no e2e configuration section is required.

- Make Oracle ojdbc11 and PostgreSQL drivers default runtime dependencies for tests and host applications; remove the resilience-dev profile.

- Immediately reject overlapping migrations with a JVM-wide single permit, released only after worker and transaction cleanup; deployment must prevent different processes from overlapping.
- Remove database execution locks, ownership tokens, configuration fingerprints, and endpoint identity digests. Retain transactional row locks and atomic data/checkpoint commits. Users prepare the new audit schema manually; old jobs are not converted.
- Change keyed-table connection budgets to W+1 on each side, and keyless budgets to source 1 / target 2. Support and verify JDK 17 only.

- Add DatabaseE2E accepting external DataSources, a main entry point, and an explicitly configured IT entry; reports validate migration, auditing, and repeated execution.
- Use the production Runner in fault-test hosts; remove test-only requirements for empty targets, append mode, and zero skipped rows.

- **Breaking redesign**: the host supplies both DataSources. The component no longer creates pools or obtains credentials. Remove old connection configuration and the automatic startup switch; unfinished jobs from older versions are incompatible.
- Add MigrationRunner without a Spring context for synchronous migration before the host calls .run(). The Spring adapter selects DataSources through source/target annotations; the host declares capacity.
- Replace three global row-count settings with per-table batch-size, default 1000, controlling keyed transaction blocks, JDBC batches, and fetch hints. Keyless tables retain a single whole-table transaction.
- Set and restore transaction properties when borrowing/returning connections; roll back unfinished transactions before return. Redact acquisition errors while preserving SQLState and vendor codes for bounded retries.
- Adapt H2/DEV resilience tests, retaining precise commit boundaries, isolated host JVMs, TCP proxies, and redacted execution manifests. DEV DataSources come from the test host.
- Rewrite host integration, configuration, decryption/IAM, ECS, and H2 documentation, and provide a runnable pre-startup example. Verify JDK 17 only and retain 85% line and branch coverage thresholds.

## 0.1.0-SNAPSHOT — 2026-10-28

Initial development release; not published to Maven Central.

- Provide an embeddable, single-module Spring Boot 4.1.1 / Java 21 JDBC migration component with a synchronous method entry point.
- Support YAML connections, pools, mappings, two-level concurrency, skipping/retries, and audit configuration.
- Persist range-based transaction checkpoints for keyed tables and support recovery; use a whole-table transaction for keyless tables.
- Provide explicit clear-and-reimport, sequence finalization, BLOB/CLOB streaming writes, and target-table execution locks.
- Provide two-H2 / 10,000-row E2E tests, positive and negative TLS tests, disconnection recovery, and process restart tests.
- Provide local DBeaver instructions, Oracle/PostgreSQL TLS guidance, and ECS integration notes. Automatic IAM refresh and real cloud validation remain pending.
