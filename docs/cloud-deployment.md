# Host runtime and deployment responsibilities

This project is a regular dependency JAR accepting usable host DataSources. The host owns authentication, TLS, pools, application containers, images, ECS tasks, and health checks. Oracle/PostgreSQL JDBC drivers remain runtime dependencies.

Use JDK 17 and the [generic host wrapper](../examples/HostMigration.java) for synchronous or asynchronous execution. See [integration](getting-started.md), [DataSource ownership](database-credentials.md), and [host execution](host-execution.md) for annotations, capacity, lifecycle, progress, and repeat calls.

Preserve job-id, tasks, actual DataSources, rules, and source ranges during recovery. JVM coordination does not coordinate different processes. Deployment must prevent overlapping migration processes and wait for cleanup before closing DataSources.

Prepare target business tables, auditing, and sequences in advance. Local H2 validation uses synthetic data. Users validate real DataSources and business data through [real database E2E](real-database-e2e.md).
