# Resilience testing

The recommended new entry is [real DataSource E2E](real-database-e2e.md): ordinary migration and four fault types share e2e configuration, with caller-supplied DataSources. The following describes local use of the original resilience entry.

Default mvn clean verify runs local H2 fixtures only. Real databases require explicit opt-in and user-prepared objects. Source data must remain stable. Tests add no schema creation, target clearing, or real business-data seeding; production migration modes keep their normal behavior.

```powershell
mvn -B -ntp verify "-Dit.test=ConfiguredResilienceIT" "-Dresilience.config=file:examples/resilience/scenarios.yml,file:examples/resilience/h2.yml" "-Dresilience.scenario=SOURCE_OUTAGE"
```

Local H2 fixtures prepare their own independent databases. Real environments can select external source/target beans using the [DEV template](../examples/resilience/oracle-postgresql.yml). The newer E2E template also accepts host-owned DataSources directly.

The parent process owns source/target TCP proxies, closes existing relays, and rejects new connections. The host runs in a child JVM. Exact business-SQL matching synchronizes with the parent at commit boundaries. After network restoration, restart the Runner with the same job/configuration, then execute again to verify data and audit counts do not accumulate.

A new job need not have empty targets. APPEND_RESUME, keyed tables, and zero skips are not forced. The trigger task uses one worker for deterministic fault ordering. Keyless pre-commit crashes can also recover a whole-table transaction. New-job conflicts, completed jobs, and explicit reimports follow production logic. Failed tests preserve the current state.

Source validation SQL describes the expected final subset and may exclude expected bad rows when skips are allowed. The complete migration source count separately verifies committed+skipped. Summary and batch totals must agree, and target digests/batch counts must remain unchanged on repetition. Compare committed target snapshots around host crashes without assuming an initially empty target.

Each run stores a redacted manifest/result. Control files and child logs are local diagnostic material. Completed jobs do not get new IDs or cleared targets just to force faults; reports honestly record faultTriggered=false. See the new guide for fields, timeouts, and routing.

Only JDK 17 is supported. The parent waits for the failed JVM to exit before starting recovery. One migration process runs at a time. Database execution locks, ownership tokens, and cross-process takeover are removed. Recovery preserves configuration, actual DataSources, and source ranges. Data/checkpoint atomicity remains.

## Failures after data commit

- H2VerificationRecoveryIT cuts the source proxy after count verification and before content scanning. The attempt records VERIFICATION_ERROR and retains data/batch auditing. Restoring the proxy allows the same Runner to verify again and mark SUCCEEDED.
- H2ProcessRecoveryIT exits the child JVM after sequence calibration and before the successful summary is written. The summary retains FINALIZING and data_complete=true. Raising the sequence before new-JVM recovery verifies that data and batch auditing remain intact and the higher sequence value is preserved.

## DEV DataSources and proxy ports

The [DEV template](../examples/resilience/oracle-postgresql.yml) contains scenarios, proxy routes, verification queries, and migration rules. Set resilience.configuration-classes to your host configuration class. It loads in the parent and each child JVM. Mark source/target beans with @MigrationSource / @MigrationTarget. Hikari capacity is automatic, with explicit capacity available for unsupported custom sources.

Use @Lazy on DataSource beans to avoid creating connections during configuration discovery. Read resilience.source-proxy.port and resilience.target-proxy.port from Spring Environment and route through the assigned local parent proxies. upstream-host/upstream-port identify the actual database. The class and resources must be available on test and subprocess classpaths. See the [configuration example](../examples/e2e/ExternalDataSourcesConfiguration.java).

Host authentication, connection properties, and certificates remain host-managed. Transparent TCP forwarding must respect server identity validation. The harness verifies real traffic through both proxies. Bypass connections cannot establish outage coverage.

examples/resilience/h2.yml is the single local H2 source. Maven maps it to classpath:resilience/h2.yml through its examples test resources.
