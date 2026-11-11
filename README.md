# data_migration

An embeddable JDBC migration component for Java 17 / Spring Boot 4.1.1. The host supplies source and target `DataSource` objects; the component handles table mappings, transactions, auditing, and checkpoint recovery. The current development version is `0.1.0-SNAPSHOT`.

## Where to start

| What you need | Documentation |
| --- | --- |
| Run before .run() or integrate with Spring annotations | [Host integration](docs/getting-started.md) |
| Understand batch-size, job-id, and mappings | [Configuration](docs/configuration.md) |
| Run synthetic examples | [Local H2](docs/h2-local.md) |
| Understand DataSource ownership, capacity, and lifecycle | [Host DataSources](docs/database-credentials.md) |
| Inspect progress, resume, or reimport | [Operations](docs/operations.md) |
| Understand deployment responsibilities | [Deployment boundaries](docs/cloud-deployment.md) |
| Supply real DataSources or use main/Maven | [Real database E2E](docs/real-database-e2e.md) |
| Simulate outages and process crashes | [Resilience testing](docs/resilience-testing.md) |
| Cut and restore source/target connections manually | [Manual proxy testing](docs/manual-host-migration.md) |
| Use synchronous/asynchronous calls and progress | [Host execution](docs/host-execution.md) |
| Diagnose failures and pre-startup logging | [Logging](docs/logging-and-troubleshooting.md) |
| Read implementation and test coverage | [Architecture](docs/architecture.md), [test index](docs/test-index.md) |
| Present the project and prepare a technical review | [Flowchart](diagrams/project-overview.svg), [review guide](diagrams/project-review-guide.md) |

## Run before host startup

Run `mvn clean install` in this repository, then add `com.datamigration:data_migration:0.1.0-SNAPSHOT` to the host. Add a connection pool of your choice; Oracle and PostgreSQL JDBC drivers are already runtime dependencies. The component has no built-in connection configuration or pool and creates no Spring context.

```java
public static void main(String[] args) {
    // HostDataSources is the host's own factory, returning closeable connection pools.
    try (var source = HostDataSources.createSource();
         var target = HostDataSources.createTarget();
         var migration = MigrationRunner.builder()
             .source(source).target(target)
             .configLocations("classpath:migration.yml")
             .arguments(args).build()) {
        migration.migrate();
    }
    SpringApplication.run(Application.class, args);
}
```

A migration exception propagates before the host starts. The component computes minimum source/target capacity from configured concurrency, reads HikariCP maximumPoolSize, and reports insufficient capacity before borrowing connections. It does not resize the pool. Closing the Runner releases its execution resources while DataSources remain host-owned.

YAML describes migration rules only; see [migration.yml](examples/migration.yml). Each table has its own `tables[].batch-size`, defaulting to 1000. For keyed tables it defines transaction blocks, the fetch hint, and the JDBC batch row limit. One call continues until migration completes. Keyless tables retain a single transaction for the entire table.

migrate() and migrateAsync() on one Runner/singleton Service share running execution state. Duplicate calls immediately return the same MigrationExecution, with progress available through progress(). After an attempt finishes, a new call creates a new execution and reads auditing to skip or resume tables. Starting a new synchronous attempt waits for completion, while an asynchronous call returns immediately. Different Runners or Spring contexts remain subject to JVM-wide exclusion. Tables and batches within one attempt can run concurrently. The permit releases after transaction and worker cleanup. Deployment must prevent overlapping processes. See [host execution](docs/host-execution.md).

## Repeated calls and recovery

The same job-id identifies one migration job; name distinguishes its table tasks. Repeated calls skip successful tasks, resume unfinished batches, or retry unfinished finalization. This is not scheduled incremental synchronization. Source data must remain unchanged throughout migration and recovery. Configuration, DataSources, and source ranges must remain unchanged when resuming; the component neither computes configuration digests nor detects configuration drift.

Create target business tables, the audit table, and required sequences manually. Default APPEND_RESUME does not clean targets or perform automatic upserts; CLEAR_AND_REIMPORT explicitly permits deleting the entire target table. See [audit design](docs/audit.md) for fields and range recovery examples. Audit SQL: [PostgreSQL](src/main/resources/db/postgresql/migration-audit.sql), [H2](src/main/resources/db/h2/migration-audit.sql).

## Call from a Spring context

Mark exactly one source and one target DataSource bean method or implementation class with @MigrationSource and @MigrationTarget, then inject MigrationService and invoke it explicitly. Starting the context does not run migration. Before .run(), use the direct-object entry point above.

For static calls, [MigrationUtil.java](examples/MigrationUtil.java) accepts existing DataSources and exposes MigrationUtil.migrate()/migrateAsync(). Inspect the returned execution or register completion notifications. See [host execution](docs/host-execution.md).

## Build and boundaries

```sh
mvn -B -ntp clean verify
```

Surefire runs `*Test`; Failsafe runs local `*IT` tests. Real DEV execution requires explicit opt-in. JaCoCo measures Surefire separately, with at least 85% line and branch coverage. Local development and CI support and verify JDK 17 only. The component is a regular dependency JAR, not a standalone executable service; Boot 2/3 compatibility is not currently promised.

This is a breaking redesign of connection configuration, batching APIs, and unfinished checkpoints. Hikari and H2 are test/demo dependencies. Real Oracle/PostgreSQL DataSources and business data require acceptance in the actual environment. Hosts own authentication, TLS, and deployment.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md).

Optional per-table count verification and percentage content sampling preserve data and checkpoints on both mismatches and query failures. Explicit retries repeat verification. For manual reimport, stop migration and clear the target and all corresponding task audit rows. Verification defaults to disabled. See [table verification](docs/table-verification.md).
