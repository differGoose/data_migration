# Changelog

## Unreleased

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
