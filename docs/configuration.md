# Configuration and per-table batching

YAML in this version contains migration rules only; the host DataSource owns connections and pools. See [host integration](getting-started.md) for a complete introduction and [migration.yml](../examples/migration.yml) for a template. Old global batching, connection, and startup-switch fields have been removed. Supplying them fails explicitly; no compatibility conversion is provided.

## Identity and recovery

job-id is the stable identifier for the entire job, shared by all tables in one configuration; tables[].name identifies each table task. runId is generated for each API invocation and is not a recovery identity. Retrying the same job-id/name resumes its progress. A new job does not automatically clear targets or deduplicate rows.

Keep both actual databases, namespaces, migration configuration, and source range unchanged during recovery. The component does not compute endpoint identities or configuration digests and does not automatically detect changes. Changing rules and then resuming the same job is unsupported.

## The only row-count parameter: tables[].batch-size

Defaults to 1000, must be >=1, and is independent per table:

```yaml
data-migration:
  job-id: combined-import
  tables:
    - name: customer
      batch-size: 1000
      # Remaining mapping configuration...
    - name: orders
      batch-size: 500
      # Remaining mapping configuration...
```

For keyed tables this value defines transaction blocks, the source fetch hint, and the target JDBC batch row limit. One migrate call continues until all tables finish. For 2500 rows and batch-size=1000, blocks are normally 1000, 1000, and 500 rows. Skipped source rows also count toward block size, so fewer rows may be written to the target.

JDBC drivers choose their own network prefetch behavior. max-batch-bytes, LOB handling, and tail blocks can make an actual executeBatch smaller than this limit without moving keyed transaction commit boundaries. With multiple workers, each block has its own limit; this is not a combined instantaneous limit for the whole table.

Keyless tables with primary-key: [] require worker-count=1 and retain a single whole-table transaction. batch-size affects fetch/batch behavior only and cannot trigger an early commit. Failure rolls back the entire table; the next attempt starts reading from the beginning. Keyless tables cannot skip bad rows.

## SQL, keys, and fields

select-sql is a SELECT supplied by a trusted administrator. insert-sql is a single-row VALUES template with ? parameters. The first fields entry corresponds to the first question mark; source-column names the query result label (use the alias when present). target-jdbc-type uses JDBCType names such as BIGINT, VARCHAR, and DECIMAL; it does not alter the target schema.

target-table must match the actual INSERT target; only plain table or schema.table identifiers are supported. primary-key lists stable, non-null, unique result columns and may represent a business unique key. Keep composite-key order, such as [TENANT_ID, ID], unchanged during recovery. SELECT does not support CTEs, runtime ? parameters, or locking queries. SQL validation does not replace read-only source permissions.

## Verification after data transfer

tables[].verification.enabled defaults to false. When enabled, sample-percent must be greater than zero and at most 100, with decimal values allowed independently per table. Verification compares source-query, whole-target-table, and audited counts before comparing sampled migrated content. Sample size is rounded up. Verification requires skip-limit=0, explicit INSERT columns, and plain question-mark parameters.

Mismatches record VERIFICATION_MISMATCH and preserve data and checkpoints. Explicit retries repeat verification without inserting or clearing again. Query/network failures also preserve state. To reimport manually, stop migration, clear the target and all task audit rows, and call the original Runner. See [verification](table-verification.md) for state and cost details.

## Concurrency and connection capacity

Synchronous/asynchronous duplicates on one instance share its running execution and progress. A JVM-wide permit prevents overlapping engines across Runners and Spring contexts while retaining internal concurrency. Deployment must prevent overlapping processes.

max-concurrent-tables controls table concurrency; worker-count controls concurrent blocks within a table:

| Table type | Source connections | Target connections |
| --- | --- | --- |
| Keyed, W workers | W+1 | W+1 |
| Keyless, 1 worker | 1 | 2 |

Use builder.source(source).target(target), or source/target annotations without capacity, for automatic HikariCP inspection. The budget sums the largest max-concurrent-tables demands on each endpoint. Two keyed single-worker tables need 4 connections per endpoint. Four two-worker tables need 12 per endpoint at table concurrency 4, or 6 at concurrency 2. A shared underlying source/target pool must satisfy their combined demand.

Insufficient capacity fails before connection borrowing or SQL, reporting the endpoint, required capacity, and maximumPoolSize. A new synchronous call throws, while asynchronous failure is exposed through completion() and safe logs. Adjust the host pool and explicitly retry to re-read capacity. The component never resizes the pool or changes concurrency automatically.

Automatic inspection supports HikariDataSource subclasses, Spring DelegatingDataSource chains, and standard JDBC unwrap. Unknown pools, unresolved routing targets, wrapper cycles, and unreadable positive limits fail explicitly. For custom/nonpooled sources, source(ds, capacity)/target(ds, capacity) and positive annotation capacities remain explicit declarations. When Hikari is recognized, a declaration cannot conceal a smaller actual maximum.

The check validates configured maxima rather than currently idle connections. Reserve business capacity or use a dedicated migration pool. Optional explicit capacity can limit a shared pool's migration allowance. Every new attempt validates capacity and reads auditing again. Successful audited tables skip source access.

The host manages pool acquisition timeouts, JDBC connect/read timeouts, connection recycling, and dynamic credentials. The Runner does not close DataSources. Borrowed connections use autoCommit=false and READ_COMMITTED; unfinished transactions are ended before restoring original autoCommit/isolation settings and returning the connection. Supply clean, independent connections, not wrappers around active business transaction Connections.

## Strategy defaults

| Parameter | Default | Meaning |
| --- | --- | --- |
| strategy.max-concurrent-tables | 4 | Concurrent tables; connection budgets must be satisfied |
| tables[].worker-count | 1 | Concurrent transaction blocks for this table |
| strategy.skip-limit | 0 | Cumulative allowed row errors per job/table; recovery does not reset it |
| strategy.transient-retry-count | 3 | Bounded transient retries after the initial attempt |
| strategy.retry-initial-backoff / retry-max-backoff | 1s / 10s | Positive exponential backoff and its cap |
| strategy.max-batch-bytes / max-lob-size | 16MB / 32MB | Estimated batch memory and single-field size limits, not a total JVM memory limit |
| strategy.audit.table-name | migration_audit | Precreated audit table; audit.enabled must be true |
| tables[].mode | APPEND_RESUME | Append and resume by default; no automatic deduplication or conflict updates |
| tables[].sequence.enabled | false | Explicitly configure a named sequence and target column; calibrate after data completes |

CLEAR_AND_REIMPORT explicitly DELETEs the entire target table, not just the source SELECT range. Recovery of the same job does not repeat an already completed preparation DELETE. New work can use a new job-id with an explicit decision about existing target data. To manually restart the same task, stop migration and clear its target data and all corresponding audit records before calling the original Runner again; see [table verification](table-verification.md#manual-reimport).

## File overrides and configuration caching

Later configLocations files override earlier files; command-line arguments take precedence. Environment variables and profiles follow Boot configuration rules. Spring replaces lists as a whole: tables/fields are not merged by name. Configuration is cached after the first successful binding; editing files does not hot-reload an existing Runner.

This version does not adopt unfinished checkpoints from earlier versions or preserve old APIs/configuration. The audit table no longer has fingerprint or internal execution-lock records; see [audit design](audit.md). Complete old jobs with the old version first. Users prepare the new audit table manually; the component performs no automatic conversion or ALTER.
