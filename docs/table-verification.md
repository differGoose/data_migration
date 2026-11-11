# Per-table count verification and content sampling

Verification is part of production migration and requires no E2E section or separate verification API. It defaults to disabled and is configured independently per table. Data commits first, followed by verification, sequence calibration, and the SUCCEEDED summary. Both public entry points use the same process.

## Configuration

Merge this fragment into a complete migration configuration. See the [full example](../examples/migration.yml).

~~~yaml
data-migration:
  job-id: customer-import
  strategy:
    skip-limit: 0
  tables:
    - name: customer
      target-table: customer_copy
      primary-key: [ID]
      worker-count: 1
      batch-size: 1000
      verification:
        enabled: true
        sample-percent: 10
      select-sql: SELECT ID, NAME FROM business.customer ORDER BY ID
      insert-sql: INSERT INTO customer_copy(id, name) VALUES (?, ?)
      fields:
        - {source-column: ID, target-jdbc-type: BIGINT}
        - {source-column: NAME, target-jdbc-type: VARCHAR}
~~~

enabled defaults to false. When enabled, sample-percent is required and must be greater than zero and at most 100. Decimal percentages such as 0.5 are supported. Other tables can independently use 20 or 100. If any enabled task uses verification, the shared skip-limit must be zero.

INSERT must explicitly list ordinary unquoted column identifiers, with one plain ? per field. Constants, generated expressions, or UPPER(?) are rejected before migration SQL executes. Trigger changes to mapped fields are compared as actual content. Additional target columns populated by defaults are excluded.

## Comparison algorithm

1. Count the original source SELECT and the entire target table. Both must equal the audit's committed-row count.
2. Select an exact random sample of ceil(source count × sample-percent / 100) source rows. At 101 rows and 10%, sample 11 rows. Nonempty tables sample at least one row, empty tables zero, and 100% samples every row.
3. Normalize all mapped fields in mapping order to target JDBC types. The custom ValueConverter applies once to source scalar values, as during migration. Target values are not passed through that custom converter again. Source LOBs use the streaming path. Numeric trailing zeros are normalized, zoned times use equivalent time semantics, and NULL, empty text, and empty binary remain distinct.
4. Compare SHA-256 row digests and multiplicities independently of query order. If sampled content appears multiple times, compare its total occurrence count on both sides. Keyless tables and duplicate rows are supported.

Both endpoints scan mapped fields. Digests are sorted in chunks using temporary files, with streaming LOB hashing. A 10% sample still scans and hashes both complete query results. Disk space must accommodate both digest streams and sorting files. Normal completion, exceptions, and cancellation clean temporary files. After a forced process kill, operators can remove leftover migration-verification-* directories from the system temporary directory. Digests contain no raw rows but should still be handled as internal data.

Sampling can miss errors in unselected content. Use 100% for full content comparison. Every verification attempt draws a new random sample. Keep the source query, configuration, and target data stable throughout migration/verification and stop concurrent business writes to targets. Verification uses existing connection budgets sequentially.

## Mismatches and execution errors

A confirmed count/content mismatch records VERIFICATION_MISMATCH in the summary and identifies the reason in error_code. The attempt fails while target data, positive batch records, counts, and prepared/data_complete/planning_complete flags remain intact. Other table tasks can continue independently.

The next explicit call on the original Runner/Service repeats verification for that table, without INSERT, preparation DELETE, or checkpoint reset. Passing verification proceeds to sequence finalization and SUCCEEDED. Persistent mismatches fail again. A new JVM reads the same stage from target auditing.

Network, timeout, conversion, and temporary-storage failures use VERIFICATION_ERROR and preserve data/checkpoints. If recording failure also fails, logs retain the original cause. The committed data_complete flag still directs the next attempt to verification.

Logs emit the safe mismatch category without business rows, keys, or digests. committedRows reaching totalRows does not establish success while the table remains VERIFICATION_MISMATCH.

## Manual reimport

You may correct target differences and explicitly verify again. If a full reimport is needed:

1. Wait for the current attempt to finish, suspend new triggers, and ensure no other migration process is using that target.
2. Clear the intended target and all audit rows for job_id + task_name, including the summary and every positive batch. Removing only the summary produces AUDIT_SUMMARY_MISSING. Removing only target data leaves a successful summary that causes skipping.
3. Prefer one target transaction for the paired deletion. Check foreign keys, triggers, and permissions. Preserve unrelated tables and jobs in the shared audit table.
4. Call the original Runner. It re-reads auditing and restarts the cleared task. Other successful audited tasks skip.

The following is a manual operator example. Replace table, job, and task names with the actual intended scope.

~~~sql
BEGIN;
DELETE FROM customer_copy;
DELETE FROM migration_audit
WHERE job_id = 'customer-import' AND task_name = 'customer';
COMMIT;
~~~

Old execution objects and other jobs' successful audit rows describe their own historical results. If changing SQL or mappings, clear corresponding checkpoints and create a new Runner to bind the new configuration.

## Audit states

The existing schema is retained. The table summary is batch_no=0, state holds the phase/result, and error_code holds a safe reason.

| State | Meaning | Next explicit attempt |
| --- | --- | --- |
| VERIFYING | Data committed, verification active | Repeat verification and sequence finalization |
| VERIFICATION_MISMATCH | Confirmed mismatch with data/checkpoints retained | Repeat verification, then finalize if it passes |
| VERIFICATION_ERROR | Verification execution failed | Repeat verification and finalization |
| FINALIZATION_FAILED | Sequence or other finalization failed | Repeat configured verification and finalization, skipping inserts |
| SUCCEEDED | Data and configured finalization finished | Return audited result without verification |

Mismatch codes are VERIFICATION_COUNT_MISMATCH and VERIFICATION_CONTENT_MISMATCH. No separate sample-percentage or verification-passed field is stored. SUCCEEDED can belong to a task with verification disabled, so interpret it with the original configuration. Previously successful tasks are not retroactively verified.

Legacy CLEANUP_PENDING and VERIFICATION_FAILED belong to the removed automatic-cleanup implementation. New code stops with LEGACY_VERIFICATION_RESET_REQUIRED while retaining the original audit state. Inspect whether old code already cleared the target, then follow the paired manual reset above.

~~~sql
SELECT job_id, task_name, state, error_code, total_rows,
       committed_rows, prepared, data_complete, planning_complete
FROM migration_audit
WHERE job_id = 'customer-import' AND batch_no = 0;
~~~
