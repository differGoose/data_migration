# Migration logging and troubleshooting

The component uses SLF4J logger `com.datamigration` with English messages. Find a call by `runId`, then locate its failure using `task`, `batch`, `stage`, and `endpoint`. Connection failures, configuration errors, and sequence finalization have distinct phases, so diagnosis does not depend on raw driver exception messages.

## Levels and correlation fields

| Level | Content |
| --- | --- |
| INFO | Configuration loading/binding, DataSource selection, connection budgets, first successful connections, table preflight, recovery/reuse, data completion, sequence finalization, final status and duration |
| DEBUG | Connection borrowing/return, range planning, and individual batch starts/commits; these details are absent at the default INFO level |
| WARN | Bounded retry count, delay and safe reason; commit verification; secondary failures while persisting failure status |
| ERROR | Original failing task, phase, property path, and safe error classification |

`runId` identifies an invocation internally. The default engine returns the same ID in `MigrationResult.runId()`. It does not replace `job-id` or alter recovery identity; building a Runner and calling migrate later are separate diagnostic operations. `job` is the configured job-id, `task` is the table task name, and positive `batch` values identify transaction blocks. `-1` means no current batch. Concurrent task messages interleave; correlate these fields rather than assuming adjacent lines belong together.

`property` identifies the configuration location to inspect, such as `data-migration.tables[0].fields[1].source-column`, without showing its value. Fields without an applicable context show `-`. Control characters in names are replaced with underscores.

## Diagnose by phase

| stage | What to check |
| --- | --- |
| CONFIG_LOAD / CONFIG_BIND | Configuration resources, working directory, classpath, YAML indentation, unknown properties, and validation constraints. Unknown-field logs contain paths only; binding errors do not print rejected values |
| DATASOURCE_RESOLVE | Both DataSources must be supplied directly, or exactly one marked Bean must exist per endpoint in Spring |
| CAPACITY_CHECK | Declared migration capacity must meet the budget: W+1 per endpoint for keyed tables, source1/target2 for keyless tables. Add budgets for concurrently running tables; see the configuration guide |
| SOURCE_CONNECT / TARGET_CONNECT | The engine begins opening the corresponding connection; the production Runner further distinguishes acquisition and transaction initialization |
| CONNECTION_ACQUIRE | Borrowing from the host DataSource failed. Use endpoint to distinguish source/target; check accounts, network, pool capacity, and acquisition timeouts |
| CONNECTION_INITIALIZE | A connection was borrowed, but reading/setting transaction attributes failed; this differs from acquisition failure |
| AUDIT_CHECK / CHECKPOINT_READ | Target audit table existence, structure, permissions, and readability of existing checkpoints |
| SQL_POLICY / SOURCE_PREFLIGHT / TARGET_PREFLIGHT | Supported SQL restrictions, executable source SELECT and target INSERT, target-table agreement, and parameter count |
| FIELD_MAPPING / FIELD_CONVERSION / KEY_PREFLIGHT | Query-result fields, conversion types, key columns, null keys, and uniqueness. Key uniqueness is checked against source SELECT results |
| SOURCE_COUNT | Source count validation; source range and data must remain unchanged during same-job recovery |
| TARGET_PREPARE / RANGE_PLAN / RANGE_PLAN_AUDIT | Target preparation, explicit clear/reimport, range planning, and durable plans. Logging itself never triggers cleanup |
| BATCH_READ / BATCH_WRITE / BATCH_COMMIT | Reading, writing, or committing a particular batch. Check task, batch, and endpoint together |
| COMMIT_VERIFY | Audit verification after a commit exception. If unresolved, status is COMMIT_UNKNOWN; restore connectivity and invoke again with the original job/configuration |
| SEQUENCE_FINALIZE | Data is complete and sequence calibration is running. Failure may produce FINALIZATION_FAILED; fix the cause and repeat the same job to retry finalization |
| AUDIT_UPDATE / AUDIT_FAILURE_REPORT | Checkpoint or summary updates. A secondary failure while recording failure status is logged separately and does not replace the original diagnostic |
| CONNECTION_CLEANUP | Rollback, transaction attribute restoration, or connection return failed |

The first precise failure context is retained in table-level ERROR messages. Additional WARN messages may describe audit persistence failures after the original batch error; investigate the original ERROR first. A failed table does not imply other tables were rolled back.

## Typical messages

Timestamps and some correlation fields are omitted below. Actual messages also include runId, job, task, batch, and property.

```text
INFO  Migration configuration loading started ... stage=CONFIG_LOAD ... locations=1 existingEnvironment=false
INFO  Migration configuration binding succeeded ...
INFO  Opening migration connection ... stage=SOURCE_CONNECT endpoint=source
ERROR Table migration failed ... stage=CONNECTION_ACQUIRE endpoint=source ... category=AUTHENTICATION_FAILURE ... SQLSTATE=28P01; vendor=0
ERROR Table migration failed ... stage=FIELD_MAPPING endpoint=source property=data-migration.tables[0].fields[1].source-column ... reason=Source column missing from query result ...
INFO  Table data committed ...
INFO  Sequence finalization started ... stage=SEQUENCE_FINALIZE ... enabled=true
ERROR Table migration failed ... stage=SEQUENCE_FINALIZE endpoint=target ...
INFO  Table migration completed ... status=FINALIZATION_FAILED committedRows=1000 skippedRows=0 elapsedMs=1200
```

SQLState and vendor codes come from JDBC. Classification uses known codes and exception types, for example authentication, connection, permission, or missing table/column. `UNKNOWN` means available safe information is insufficient; do not conclude that credentials or networking caused it. Pool acquisition and network timeouts can share codes; inspect host pool metrics to distinguish them.

Printing only `MigrationException.getMessage()` still provides failed tasks, statuses, and existing safe error summaries. Use logs from the same call for detailed phases; `result()` continues to expose per-table outcomes. Avoid dumping full exception chains for diagnosis: driver and host pool messages can contain SQL, URLs, or secrets.

## Configure logging before SpringApplication.run

Runner configuration loading does not initialize Spring Boot logging. Before `.run()`, the host's active SLF4J backend controls output; do not assume `logging.level` in migration.yml or application.yml has already taken effect. The host must provide a working backend. The component adds no logging framework.

Logback hosts can use ordinary `src/main/resources/logback.xml`, or `logback-test.xml` for tests, as below. Before startup, use standard Logback configuration rather than extension tags that require Boot initialization.

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="com.datamigration" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

Alternatively, point the JVM at host configuration with `-Dlogback.configurationFile=/path/to/logback.xml`. Set this logger to INFO for milestones only. Calls after container startup can also use Boot's `logging.level.com.datamigration`. No logging properties need to be added under `data-migration`.

[BeforeStartupDemo](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java) migrates before starting the host; run its main in an IDE with the test classpath to observe the order. [Simulated real host tests](manual-host-migration.md) use the same production entry; users provide real database settings.

If the host DataSource factory fails before Runner is called, the component has not gained control and cannot log that step. The host should add its own safe factory-stage diagnostics. At every level, component messages exclude raw driver messages, stacks, SQL, parameters, business rows, key boundaries, JDBC URLs, passwords, and tokens. Other host, pool, and driver loggers remain the host's responsibility.
