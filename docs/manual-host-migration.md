# Manually cut real database connections and resume migration

Use [HostMigrationE2EIT](../src/test/java/com/datamigration/e2e/HostMigrationE2EIT.java). Ordinary mode creates host DataSources and calls MigrationRunner once. Manual mode adds two test TCP proxies and a local control page. Both read only production data-migration configuration, with no e2e section required.

## Connection and proxy addresses

Fill the connection settings at the top of the test locally; do not commit real details. Manual mode requires:

| Constant | Value |
| --- | --- |
| SOURCE_UPSTREAM_HOST / PORT | Real Oracle address and port; default port 1521 |
| TARGET_UPSTREAM_HOST / PORT | Real PostgreSQL address and port; default port 5432 |
| SOURCE_JDBC_URL | Oracle URL template pointing at the local proxy, with the actual service name |
| TARGET_JDBC_URL | PostgreSQL URL template pointing at the local proxy, with the actual database name |
| SOURCE_USERNAME / PASSWORD, TARGET_USERNAME / PASSWORD | Corresponding real database accounts and passwords |

Illustrative URL formats, without credentials:

```java
SOURCE_JDBC_URL = "jdbc:oracle:thin:@//127.0.0.1:{proxy-port}/YOUR_SERVICE";
TARGET_JDBC_URL = "jdbc:postgresql://127.0.0.1:{proxy-port}/YOUR_DATABASE";
```

Keep `{proxy-port}` unchanged; the test allocates two local ports and replaces each template separately. Traffic flows from host pool to local proxy to real database. Do not keep direct real-server addresses in JDBC URLs, or cutting the proxy will not affect migration. Restore ordinary direct URLs when leaving manual mode.

The proxy forwards TCP bytes transparently; the database and driver still perform TLS. Preserve trust and server-identity verification. If the URL must retain the certificate hostname, resolve that name locally to the proxy and ensure upstream independently reaches the real database without looping back. Do not disable certificate verification. Oracle redirection, multiple hosts, and failover addresses beyond a single endpoint also require controlled routing; the proxy cannot cut bypass connections. The host configures connect/read timeouts for its actual driver.

## Configuration and startup

Use production data-migration rules in `src/test/resources/application.yml`, including a stable job-id, mappings, batch-size, and strategy. Prepare target business/audit tables in advance. The test does not add schema creation, clearing, or seeding. Explicit CLEAR_AND_REIMPORT still deletes target data according to production engine rules.

Add two VM arguments to the IDE JUnit run configuration:

```text
-Dhost.e2e.enabled=true -Dhost.e2e.manual=true
```

Or use Maven:

```powershell
mvn -B -ntp test-compile failsafe:integration-test failsafe:verify "-Dit.test=HostMigrationE2EIT" "-Dhost.e2e.enabled=true" "-Dhost.e2e.manual=true"
```

The console prints `Manual migration control: http://127.0.0.1:PORT/RANDOM_PATH/`. Open the complete URL in a browser. The page listens only locally; its random path restricts control access. Do not publicly share this address.

## Manually disconnect and resume

1. Click "Run / Resume". Once RUNNING, click "Cut source" or "Cut target". The proxy closes existing connections and rejects new ones.
2. Keep it cut until the current attempt becomes FAILED. Existing bounded transient retries still apply; restoring too early may let the same attempt succeed through retry, which is expected.
3. Click "Restore source" or "Restore target". This only permits new connections; it does not restart migration or revive closed JDBC connections.
4. Click "Run / Resume" again. The simulated host first evicts old pooled connections so the pool establishes fresh physical connections; DataSource and Runner objects stay the same. Original configuration/job-id recover unfinished blocks and skip successful ones. Repeated clicks while running are rejected. Explicit reconnection is test-host behavior, not new automatic engine recovery.
5. Once the latest attempt is SUCCEEDED, click "Finish test after success" to end JUnit normally. You may first click "Run / Resume" again to observe completed-job behavior.

"Abort test" fails the test, requests cancellation, waits for worker/transaction cleanup, and closes resources. Sessions default to 30 minutes and fail on timeout. Set `-Dhost.e2e.manual-timeout-seconds=3600` for one hour. Drivers that ignore interruption still require their network timeout before cleanup finishes.

The control page retains failure state for manual recovery instead of exiting after an expected outage or automatically rerunning forever. At least one successful final attempt is required to finish. The page prints no business rows, connection configuration, or raw driver exceptions. Inspect the target audit table for migration details.

Keep configuration, actual databases, and source ranges unchanged during same-job recovery. An already successful job may return too quickly to inject a fault. Plan a new job and target-data handling under normal migration rules when new copying is needed; do not delete checkpoints to manufacture a fault.

## Validation boundaries

Users supply real Oracle/PostgreSQL connections for manual mode. Local [ManualHostRecoveryIT](../src/test/java/com/datamigration/e2e/ManualHostRecoveryIT.java) prepares its own synthetic H2 to verify actual TCP disconnects, same-Runner recovery, and repeated calls. The real entry never uses it as a default or fallback environment.

## Find the current migration phase

The production Runner emits key INFO milestones, with separate phases for source/target connections, table preflight, data commits, and sequence finalization. Correlate messages by runId, task, and batch. For batch details, set com.datamigration to DEBUG in the host's logback-test.xml. See [logging and troubleshooting](logging-and-troubleshooting.md) for examples and configuration. Calls before host startup cannot rely on Boot logging.level settings that have not yet been applied.
