# Resilience testing

The recommended new entry is [real DataSource E2E](real-database-e2e.md): ordinary migration and four fault types share e2e configuration, with caller-supplied DataSources. The following describes local use of the original resilience entry.

Default mvn clean verify runs local H2 fixtures only. Real databases require explicit opt-in and user-prepared objects. Source data must remain stable. Tests add no schema creation, target clearing, or real business-data seeding; production migration modes keep their normal behavior.

```powershell
mvn -B -ntp verify "-Dit.test=ConfiguredResilienceIT" "-Dresilience.config=file:examples/resilience/scenarios.yml,file:examples/resilience/h2.yml" "-Dresilience.scenario=SOURCE_OUTAGE"
```

The local H2 fixture prepares isolated databases. Real environments can register external source/target annotated beans through the [legacy DEV template](../examples/resilience/oracle-postgresql.yml). Prefer the new e2e template, which does not pass connection configuration to the component.

The parent process owns source/target TCP proxies, closes existing relays, and rejects new connections. The host runs in a child JVM. Exact business-SQL matching synchronizes with the parent at commit boundaries. After network restoration, restart the Runner with the same job/configuration, then execute again to verify data and audit counts do not accumulate.

A new job need not have empty targets. APPEND_RESUME, keyed tables, and zero skips are not forced. The trigger task uses one worker for deterministic fault ordering. Keyless pre-commit crashes can also recover a whole-table transaction. New-job conflicts, completed jobs, and explicit reimports follow production logic. Failed tests preserve the current state.

Source validation SQL describes the expected final subset and may exclude expected bad rows when skips are allowed. The complete migration source count separately verifies committed+skipped. Summary and batch totals must agree, and target digests/batch counts must remain unchanged on repetition. Compare committed target snapshots around host crashes without assuming an initially empty target.

Each run stores a redacted manifest/result. Control files and child logs are local diagnostic material. Completed jobs do not get new IDs or cleared targets just to force faults; reports honestly record faultTriggered=false. See the new guide for fields, timeouts, and routing.

Only JDK 17 is supported. The parent confirms the faulted host JVM has exited before starting recovery; only one migration process runs at a time. The new version has no database execution locks, ownership tokens, or cross-process takeover tests. Recovery keeps original configuration, actual DataSources, and source ranges unchanged; transactional checkpoints and atomic data commits remain guaranteed.
