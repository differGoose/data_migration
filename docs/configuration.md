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

## Concurrency and connection capacity

A shared JVM permit allows only one migrate execution; overlapping calls fail immediately across different Runners and Spring contexts. Concurrency inside one execution is unaffected. Deployment must prevent overlapping processes.

max-concurrent-tables controls table concurrency; worker-count controls concurrent blocks within a table:

| Table type | Source connections | Target connections |
| --- | --- | --- |
| Keyed, W workers | W+1 | W+1 |
| Keyless, 1 worker | 1 | 2 |

Two keyed tables with one worker each require at least source 4 / target 4 when running together. The host declares migration capacity using builder.source/target or the source/target annotations' capacity. The component validates the budget before execution; declarations do not resize pools. Shared business pools need additional capacity reserved for business traffic.

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

CLEAR_AND_REIMPORT explicitly DELETEs the entire target table, not just the source SELECT range. Recovery of the same job does not repeat an already completed preparation DELETE. A fresh full reimport requires a new job-id and an explicit decision about existing target data.

## File overrides and configuration caching

Later configLocations files override earlier files; command-line arguments take precedence. Environment variables and profiles follow Boot configuration rules. Spring replaces lists as a whole: tables/fields are not merged by name. Configuration is cached after the first successful binding; editing files does not hot-reload an existing Runner.

This version does not adopt unfinished checkpoints from earlier versions or preserve old APIs/configuration. The audit table no longer has fingerprint or internal execution-lock records; see [audit design](audit.md). Complete old jobs with the old version first. Users prepare the new audit table manually; the component performs no automatic conversion or ALTER.
