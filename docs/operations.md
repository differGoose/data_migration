# Migration results, failure recovery, and troubleshooting

This guide is for migration operators. Start with [getting-started.md](getting-started.md), read parameter definitions in [configuration.md](configuration.md), and use [resilience-testing.md](resilience-testing.md) for automated fault injection.

## Before execution

Keep the source query range unchanged throughout migration/recovery, pause target business writes, and confirm business/audit tables exist. Start with a small range of one table and record job-id, configuration version, and manual acceptance results. Do not change configuration or clear auditing while running or before same-job recovery. The component does not detect drift; operators must retain and reuse the original configuration.

Only one migration process may exist at a time. The JVM rejects overlapping calls immediately, but different processes are uncoordinated. Even single-node deployments must wait for the old migration process to exit before starting a new one.

Table tasks must be independently executable. List order is not a foreign-key dependency scheduler. If parents must finish before children, the host must run separate phases and manage their configurations. The framework does not automatically disable constraints or triggers.

## Status meanings

| TableResult.status | Meaning | Operator action |
| --- | --- | --- |
| SUCCEEDED | This table task and finalization completed | Check committed/skipped counts and business acceptance |
| FAILED | The task failed; some blocks may already have committed | Fix the cause and resume with the original job-id |
| COMMIT_UNKNOWN | The database commit outcome cannot currently be confirmed | Restore connectivity and verify with the original job-id; do not blindly append under a new job |
| FINALIZATION_FAILED | Data completed, but sequence or other finalization failed | Fix finalization and retry with the original job-id |

One table failure does not mean all other tables rolled back. The engine waits for tables and returns their results through MigrationException.result(). Report the invocation as failed or partially complete. Configuration, bean, and pool initialization errors may throw other exceptions.

## Resume after a network outage

1. Preserve partial target data and the audit table, record the original job-id, and confirm the old host process has stopped.
2. Restore network/database availability and verify driver timeouts and account permissions.
3. Preserve source ranges, both endpoint identities, task names, SQL, fields, keys, and critical strategies.
4. Restart the host with the original configuration and call migrate(). Keyed tasks continue unsuccessful ranges without reinserting successful ranges.
5. Recheck target contents and audit summaries. Calling a successful task again with the original job-id does not copy the full table again.

A keyless failure rolls back the whole target-table transaction and rereads the entire table next time. It still uses the same recovery identity but has no keyed block-resume capability.

Restoring the database without a host invocation does not launch a new migration. After bounded transient retries are exhausted, errors return to the host.

## Inspect progress in the target database

These read-only examples use the default audit-table name; replace it if schema/table-name differs. Replace job-id with your actual identifier:

```sql
SELECT task_name, state, total_rows, committed_rows, skipped_rows,
       processed_rows, retries, updated_at
FROM migration_audit
WHERE job_id = 'customer-import-20261029' AND batch_no = 0;

SELECT task_name, batch_no, state, expected_rows, committed_rows,
       skipped_rows, error_code
FROM migration_audit
WHERE job_id = 'customer-import-20261029' AND batch_no > 0
ORDER BY task_name, batch_no;
```

batch_no=0 is the summary; positive numbers are transaction blocks. Do not SUM summaries and batches together or counts will double. Internal audit states may be more detailed than API statuses, and a non-SUCCEEDED status does not imply no data was written. The new version creates only actual task summaries and batches, not internal execution-lock records. See [audit design](audit.md) for fields and ranges.

Audit boundaries may contain business primary keys; do not export them publicly. Error categories are redacted. Preserve the failure state for diagnosis; do not manually mark success or delete checkpoints to bypass an active migration.

## Common symptoms

| Symptom | Likely causes and checks |
| --- | --- |
| Host starts without migration | The component does not migrate automatically; check whether main calls the Runner before .run(), or context code actually calls migrate() |
| MigrationService bean missing | Check runtime classpath and Boot autoconfiguration; the baseline is Boot 4.1.1 |
| Driver class missing | Oracle/PostgreSQL drivers are transitive dependencies; inspect exclusions, scope, and packaging. Add drivers for other databases in the host |
| migration-local.yml missing | Check process working directory and spring.config.import; IDE/container relative directories may differ |
| Missing configuration or binding failure | Check template spelling, environment variables, and indentation; unknown migration fields are strictly rejected |
| Insufficient connection budget | Calculate source/target connections for concurrent table combinations, or reduce table/worker concurrency |
| Duplicate-key error | Check whether a new job-id is appending to a nonempty target or outside writers are active; do not hide it by raising skip-limit |
| A migration is already running | Another migration is active in the JVM; this call is rejected immediately. Wait for complete cleanup before retrying, rather than calling different Runners concurrently |
| Password decryption/IAM connection failure | Check the host DataSource factory, then independently validate decryption/IAM, account permissions, and TLS; see the host DataSource guide |
| Correct row count but incorrect contents | Counts do not validate contents. Sample by unique key or compare all fields; check conversion types and source-range changes |

## Start a new migration from scratch

Distinguish recovery from new work. Recovery retains the job-id. New work uses a new job-id and an explicit decision about existing target data. APPEND_RESUME neither clears targets nor automatically deduplicates existing rows.

Use CLEAR_AND_REIMPORT only when the entire target table can be rebuilt. It DELETEs the whole target table; a restricted source SELECT does not restrict that DELETE. Confirm business constraints, foreign keys, triggers, and rollback cost first. Real E2E follows production configuration and does not additionally forbid this mode; users must explicitly authorize its whole-table deletion behavior.

Do not create new work by arbitrarily deleting audit records: the old target data remains, while the new execution loses reliable committed-progress information.
