# Host DataSource responsibilities and lifecycle

Supply two usable DataSources. The host manages addresses, accounts, decryption, IAM, TLS, pools, and connect/read timeouts. The component supplies no credential interface, sets no JVM SSL properties, and preserves host connection settings.

## Supplying DataSources

Pass existing source and target DataSources to MigrationRunner. HikariCP maxima are inspected automatically. For Spring, mark host beans with @MigrationSource / @MigrationTarget. See [integration](getting-started.md) and the [generic wrapper](../examples/HostMigration.java).

The component computes minimum connection demand from YAML and reads HikariCP maximumPoolSize, failing before borrowing if capacity is insufficient. Hosts reserve business capacity in shared pools. Custom sources can use explicit capacity. Migration borrows independent connections, sets transaction properties, ends unfinished transactions, and returns connections. Do not supply wrappers over active business transaction Connections.

## Lifecycle

Keep a long-lived Runner or singleton MigrationService to share running execution state. Runner.close() stops migration and waits for cleanup without closing host DataSources. Close DataSources afterward using host resource management. Keep the Runner's try-with-resources scope open while asynchronous work is running.

See [host execution](host-execution.md) for calls, progress, and completion notifications. YAML contains migration rules rather than connection credentials.

## Connection failures

First verify source.getConnection() and target.getConnection() independently. The host and driver manage authentication/TLS failures. Component logs identify the endpoint, stage, and safe error code. See [troubleshooting](logging-and-troubleshooting.md). Avoid exposing passwords, tokens, complete URLs, or driver exception chains.
