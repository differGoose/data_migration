# Synchronous and asynchronous execution and progress

Keep a long-lived MigrationRunner or singleton MigrationService. Both methods share running execution state, so hosts need no additional AtomicBoolean, migration executor, or execution lock.

## Entry-point behavior

| State at invocation | migrate() | migrateAsync() |
| --- | --- | --- |
| No previous attempt | Starts and waits, returning MigrationExecution on success or throwing the failure | Starts background execution and returns MigrationExecution immediately |
| Running | Returns the same execution immediately | Returns the same execution immediately |
| Previous attempt succeeded | Creates a new execution, reads auditing, and waits | Returns a new execution that reads auditing in the background |
| Previous attempt failed and cleanup finished | Creates a new execution and waits for audit-based recovery | Creates a new background recovery execution |

Both methods now return MigrationExecution rather than MigrationResult. Replace direct migrate().tables() access with the completion result below. A synchronous duplicate can return before completion, so its return alone does not establish success.

Only calls on the same Runner/Service share an execution. Different Runners or Spring contexts are subject to JVM-wide mutual exclusion and fail on overlap. Deployment must prevent overlapping processes.

## Host wrapper

Copy [HostMigration.java](../examples/HostMigration.java) and retain one Runner as a service member. HikariCP demand and actual pool maxima are checked automatically.

~~~java
private final MigrationRunner migration;

public HostMigration(DataSource source, DataSource target) {
    migration = MigrationRunner.builder()
            .source(source)
            .target(target)
            .configLocations("classpath:migration.yml")
            .build();
}

public MigrationExecution triggerAsync() {
    return migration.migrateAsync();
}

public MigrationExecution triggerSync() {
    return migration.migrate();
}
~~~

These are members and methods of a host service. Spring hosts can instead inject MigrationService. Initial binding, bean selection, and connections occur during background execution. Runner.build() still loads configuration resources, so missing files can fail during build.

~~~java
MigrationExecution first = migration.migrateAsync();
MigrationExecution again = migration.migrate();
// If the first attempt is still running, first == again.
MigrationProgress progress = again.progress();
~~~

Do not create/close a Runner on every trigger or immediately leave its try-with-resources scope after asynchronous submission. close() stops execution and waits for transaction and worker cleanup. Close the long-lived Runner at host shutdown, then let the host close its own DataSources.

## Static Spring utility

Copy the single [MigrationUtil.java](../examples/MigrationUtil.java) file, adjust its package, and include it in component scanning or @Import(MigrationUtil.class). Change sourceDataSource/targetDataSource qualifiers to the actual host bean names and CONFIG_LOCATION to the migration YAML. Hikari capacity is automatic. Choose one integration approach if another MigrationService registration already exists.

After Spring initializes the configuration, ordinary host classes can call the static methods directly.

~~~java
MigrationExecution execution = MigrationUtil.migrateAsync();
// A running duplicate reuses the execution. A completed attempt starts a new one.
MigrationExecution repeated = MigrationUtil.migrate();

// Reading this execution's progress does not start or retry a task.
MigrationProgress progress = execution.progress();
System.out.println(progress.status());
System.out.println(progress.committedRows());
System.out.println(progress.tables());

execution.completion().whenComplete((result, failure) -> {
    if (failure == null) {
        // Inspect result.tables().
    } else {
        // Use execution.progress() and safe component logs to diagnose the failure.
    }
});
~~~

For a new synchronous attempt, call MigrationUtil.migrate(). Spring shutdown releases the Runner and clears the static reference. Calls before initialization or after shutdown fail explicitly. The static example allows one owning Spring context per JVM. For plain Java or use outside a context, use HostMigration and manage its lifecycle directly.

## Progress and completion

~~~java
MigrationExecution execution = migration.migrateAsync();
MigrationProgress progress = execution.progress();
System.out.println(progress.runId());
System.out.println(progress.status());
for (TableProgress table : progress.tables()) {
    System.out.println(table.taskName() + ": " + table.state()
            + ", total=" + table.totalRows()
            + ", committed=" + table.committedRows());
}
~~~

progress() returns a thread-safe immutable snapshot without SQL, connection borrowing, or retries. Previously saved snapshots stay unchanged. Call it again for current state. Calling migrate/migrateAsync after an attempt ends starts another attempt rather than merely reading progress.

| Field | Meaning |
| --- | --- |
| runId | Attempt ID shared by logs and default-engine result, regenerated for each attempt while job-id stays stable |
| status | RUNNING, SUCCEEDED, or FAILED, with detailed failures in per-table state |
| jobId / tables | May be null/empty before binding finishes |
| totalRows | Source total per table, null until counting or checkpoint restoration |
| committedRows / skippedRows | Confirmed committed counts, excluding pending transactions |
| state | PENDING, PREFLIGHT, RUNNING, VERIFYING, FINALIZING, or final TableResult state |
| sanitizedError | Safe code excluding driver messages, SQL, addresses, and business data |

Progress changes at transaction boundaries. A keyless table reports zero committed rows until its whole-table commit. Data-complete tasks can still be RUNNING overall while verifying/finalizing. Unconfirmed commit outcomes are excluded from confirmed progress.

Known display limitation: when a target outage also prevents audit reads during failure reporting, the failure snapshot's committed/skipped counts may fall to zero. Persisted data/checkpoints remain authoritative when connectivity returns.

To wait for an existing execution explicitly:

~~~java
MigrationResult result = execution.completion().toCompletableFuture().join();
~~~

join() wraps failures in CompletionException. MigrationException retains result(). A new synchronous migrate() propagates the original failure directly. Cancelling a derived future does not stop migration. Use Runner.close() or close the corresponding Spring service. Interrupting the synchronous caller waiting for its new attempt requests termination, waits for cleanup, and preserves the interrupted flag.

## Recovery and restart

Completed execution objects retain their historical result in memory. Every subsequent migration call creates a new attempt that reads target auditing. Successful tables skip source connections, business verification, and sequences. An unreadable audit fails the attempt. A new JVM/Runner uses the same persisted recovery state. Keep job-id, task names, DataSources, configuration, and source ranges stable. Recovery requires an explicit call after failure, in addition to existing bounded transient retries.

Custom MigrationEngine implementations retain the synchronous internal SPI, called in the background. Without observer integration, table counts update when the engine returns its final result. Live commit progress describes the default JdbcMigrationEngine.

Verification mismatches retain data and checkpoints under VERIFICATION_MISMATCH. Query errors use VERIFICATION_ERROR. An explicit retry repeats verification and finalization. To reimport manually, stop migration, clear the target and all corresponding task audit rows, then call the original Runner. See [table verification](table-verification.md).

## Invocation logging

Every call logs INFO overall status, completion, table phases, and committed/skipped counts. Background completion logs the final snapshot. Initial unknown configuration uses tablesKnown=false and unknown totals remain unknown. Logging uses memory snapshots without synchronous configuration loading or database reads. See [logging](logging-and-troubleshooting.md).
