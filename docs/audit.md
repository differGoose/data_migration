# Audit table: jobs, transaction blocks, and recovery boundaries

The audit table persists progress in the target database. Checkpoints commit with business data, so completed ranges can still be identified after process exit. Execution coordination uses a JVM memory permit and does not create audit records.

## job-id and task names

`job-id` is a stable identifier for a migration job, such as `customer-and-orders-import`. The customer and orders tasks share that job-id and are distinguished by `tables[].name`. Each table does not need its own job-id.

The primary key is `(job_id, task_name, batch_no)`:

- `batch_no=0`: table summary and phase state, one record per table.
- `batch_no>0`: table transaction blocks numbered from 1; each stores its range, expected count, and commit result.

Restarting the same job/task reads these records: successful tables return immediately, while unfinished tables resume unsuccessful blocks. Each API invocation's runId distinguishes execution reports and does not affect recovery. A new job-id means new work; default append mode neither clears targets nor deduplicates against existing rows.

The new version creates no internal `__table_lock__` records and stores no LOCK marker, ownership token, or fingerprint. The caller must keep configuration, actual DataSources, and source ranges unchanged during recovery; configuration drift is not detected. To change migration rules, plan new work and target-data handling instead of reusing old checkpoints.

## Field reference

| Field | Meaning |
| --- | --- |
| job_id / task_name / batch_no | Composite primary key identifying the job, table task, and transaction block |
| target_table | Resolved target-table name for locating the task |
| state | PLANNED is pending, RUNNING is in progress, SUCCEEDED is complete; summaries may also be FINALIZING, FAILED, COMMIT_UNKNOWN, or FINALIZATION_FAILED |
| lower_key / upper_key | Keyed block boundaries, lower exclusive and upper inclusive; typed JSON values, not concatenated SQL |
| expected_rows | Source rows expected in this block, including allowed skips; the summary does not use this field for the total |
| committed_rows | Committed writes in the block; the summary accumulates all successful blocks |
| skipped_rows | Committed skips, subject to the cumulative per-table skip-limit |
| processed_rows | Rows read when a block succeeds; a keyless summary may record uncommitted processing progress, not committed data |
| retries | Recorded transient retries; accumulated in the summary and counted separately per batch |
| total_rows | Full source-query count recorded during summary preparation; checked for source-count changes on recovery |
| prepared | Summary flag for completed preparation; a keyed reimport DELETE commits with this flag and is not repeated on recovery |
| planning_complete | Summary flag indicating all keyed ranges are planned; existing pending blocks must still finish |
| data_complete | Summary flag indicating the data phase is complete; only sequence or other finalization remains |
| error_code | Redacted error category, SQLState, or vendor code; no business rows or raw exception messages |
| started_at / updated_at | Record creation time and latest update time |

Phase flags primarily apply to summaries. A keyless table has one whole-table block and does not use planning_complete to determine success. After errors, blocks may remain PLANNED or RUNNING. Recovery uses persisted blocks that are not SUCCEEDED, rather than inferring progress from the last log line.

To count committed rows, query only `batch_no=0` or sum only positive batch numbers. Do not SUM both together, which double-counts rows. processed_rows does not imply a committed transaction.

## Consecutive ID example: why the first lower bound is empty

Source IDs are 1 through 2500, with batch-size=1000:

| batch_no | lower_key (illustrative) | upper_key (illustrative) | Actual range | expected_rows |
| --- | --- | --- | --- | --- |
| 1 | NULL | 1000 | ID <= 1000 | 1000 |
| 2 | 1000 | 2000 | ID > 1000 AND ID <= 2000 | 1000 |
| 3 | 2000 | 2500 | ID > 2000 AND ID <= 2500 | 500 |

The first NULL means no lower-bound condition, not a request to read null primary keys. Keyed source queries preflight keys for non-nullness and uniqueness. An actual BIGINT boundary is stored as `[{"jdbcType":-5,"text":"1000"}]` and restored to the corresponding JDBC type for parameter binding.

Boundaries come from actual sorted keys, not by adding 1000 to an ID. With source IDs 10, 90, and 300 and batch-size=2, the first block is `(NULL,90]` and the second is `(90,300]`.

## Composite-key example

With primary-key `[TENANT_ID, ID]`, sorted rows `(1,10), (1,20), (2,5), (2,9)`, and batch-size=2:

- First block: no lower bound, upper bound `(1,20)`; reads the first two rows.
- Second block: `((1,20),(2,9)]`; reads the last two rows.

Composite keys use lexicographic order. The second block's lower condition is equivalent to `TENANT_ID > 1 OR (TENANT_ID = 1 AND ID > 20)`; its upper condition also prevents results beyond `(2,9)`. Keys are bound parameters, and JSON array order matches primary-key.

With parallel workers, block 2 may succeed before block 1 fails. Saving only the maximum ID or composite key and restarting after it would miss block 1. Persisting each block's range and status preserves those earlier gaps, allowing recovery to redo only unsuccessful blocks.

Keyless tables have empty lower/upper bounds: the full source query is one target transaction. batch-size affects fetch hints and JDBC batches, not early commits. Failure rolls back the whole table, and the next attempt starts from the beginning.

## Why database row locks remain

The JVM permit excludes overlapping executions. Within one execution, multiple workers still commit concurrently. Batch and summary row locks protect checkpoints, cumulative counts, and shared skip budgets. When a commit response is lost, verification also waits for the original transaction to finish. These transactional locks release on commit or rollback and require no separate execution-lock records.

The permit releases only after workers finish and transactions/connections are cleaned up. Overlapping calls from different Runners or Spring contexts fail immediately. Different processes are not coordinated; deployment must ensure the previous process exits before a recovery process starts.

## Schema preparation and version changes

DDL: [PostgreSQL](../src/main/resources/db/postgresql/migration-audit.sql), [H2](../src/main/resources/db/h2/migration-audit.sql). The running engine neither creates tables, alters old schemas, nor converts old checkpoints.

Finish unfinished old-version jobs with that version first. Users manually prepare the new audit schema; a separate new audit table is recommended to retain history. Switch after no old jobs remain unfinished. Do not run the new version directly against an old table with a mandatory fingerprint column: inserts would fail because that column is no longer supplied. Local synthetic test fixtures manage only their own schemas.
