# data_migration

An embeddable JDBC migration component for Java 17 / Spring Boot 4.1.1. The host supplies source and target `DataSource` objects; the component handles table mappings, transactions, auditing, and checkpoint recovery. The current development version is `0.1.0-SNAPSHOT`.

## Where to start

| What you need | Documentation |
| --- | --- |
| Migrate before the host calls `.run()`, or integrate through Spring annotations | [Host integration](docs/getting-started.md) |
| Understand per-table batch-size, job-id, and field mappings | [Configuration](docs/configuration.md) |
| Run examples with synthetic data | [Local H2 guide](docs/h2-local.md) |
| Supply custom decryption and IAM DataSources | [Host credentials and DataSources](docs/database-credentials.md) |
| Inspect progress, resume failures, or reimport all data | [Operations](docs/operations.md) |
| Configure TLS and ECS integration | [Cloud deployment](docs/cloud-deployment.md) |
| Pass real DataSources directly to E2E, or run through main/Maven configuration | [Real database E2E](docs/real-database-e2e.md) |
| Simulate network outages and host crashes | [Resilience testing](docs/resilience-testing.md) |
| Manually cut/restore source or target connections and resume the original job | [Manual host proxy testing](docs/manual-host-migration.md) |
| Find implementation details and tests | [Architecture](docs/architecture.md), [test index](docs/test-index.md) |

## Run before host startup

Run `mvn clean install` in this repository, then add `com.datamigration:data_migration:0.1.0-SNAPSHOT` to the host. Add a connection pool of your choice; Oracle and PostgreSQL JDBC drivers are already runtime dependencies. The component has no built-in connection configuration or pool and creates no Spring context.

```java
public static void main(String[] args) {
    // HostDataSources is the host's own factory, returning closeable connection pools.
    try (var source = HostDataSources.createSource();
         var target = HostDataSources.createTarget();
         var migration = MigrationRunner.builder()
             .source(source, 4).target(target, 4)
             .configLocations("classpath:migration.yml")
             .arguments(args).build()) {
        migration.migrate();
    }
    SpringApplication.run(Application.class, args);
}
```

Migration exceptions propagate and prevent the real host from starting. `4` declares the connections reserved for migration; it does not resize the pool. Source and target capacities are declared separately. Closing the Runner releases its execution resources without closing host DataSources.

YAML describes migration rules only; see [migration.yml](examples/migration.yml). Each table has its own `tables[].batch-size`, defaulting to 1000. For keyed tables it defines transaction blocks, the fetch hint, and the JDBC batch row limit. One call continues until migration completes. Keyless tables retain a single transaction for the entire table.

Default engines in the same JVM share one in-memory permit. Overlapping migration calls from any Runner or Spring context immediately throw IllegalStateException. Table and batch concurrency within one migration remains available. The permit is released after transaction and worker cleanup; deployment must prevent migrations in different processes from overlapping.

## Repeated calls and recovery

The same job-id identifies one migration job; name distinguishes its table tasks. Repeated calls skip successful tasks, resume unfinished batches, or retry unfinished finalization. This is not scheduled incremental synchronization. Source data must remain unchanged throughout migration and recovery. Configuration, DataSources, and source ranges must remain unchanged when resuming; the component neither computes configuration digests nor detects configuration drift.

Create target business tables, the audit table, and required sequences manually. Default APPEND_RESUME does not clean targets or perform automatic upserts; CLEAR_AND_REIMPORT explicitly permits deleting the entire target table. See [audit design](docs/audit.md) for fields and range recovery examples. Audit SQL: [PostgreSQL](src/main/resources/db/postgresql/migration-audit.sql), [H2](src/main/resources/db/h2/migration-audit.sql).

## Call from a Spring context

The host marks two DataSource @Bean methods or implementation types with `@MigrationSource(capacity=4)` and `@MigrationTarget(capacity=4)`, then injects `MigrationService` and calls it explicitly. Each role must be unique. Starting a context does not automatically run migration. Beans do not yet exist before `.run()`; use the direct-object entry above for that case.

## Build and boundaries

```sh
mvn -B -ntp clean verify
```

Surefire runs `*Test`; Failsafe runs local `*IT` tests. Real DEV execution requires explicit opt-in. JaCoCo measures Surefire separately, with at least 85% line and branch coverage. Local development and CI support and verify JDK 17 only. The component is a regular dependency JAR, not a standalone executable service; Boot 2/3 compatibility is not currently promised.

This is a breaking redesign: old connection configuration, old batching parameters, and unfinished checkpoints from earlier versions are incompatible. Hikari and H2 are test/demo dependencies only, not host runtime dependencies supplied by the component. Real Oracle/PostgreSQL, IAM, and cloud deployment still require validation in the actual environment.

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution rules and [CHANGELOG.md](CHANGELOG.md) for changes.
