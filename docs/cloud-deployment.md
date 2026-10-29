# Oracle TCPS, PostgreSQL TLS, and ECS integration

These are deployment instructions, not a cloud acceptance report. Local H2 TLS tests cover trusted-certificate success and untrusted-certificate rejection; real Oracle/PostgreSQL drivers, IAM, and ECS still require DEV validation. H2 compatibility mode does not replace those checks.

## Recommended integration sequence

1. Follow [host integration](getting-started.md) with ordinary configuration to establish method calls, target schema preparation, and field mappings.
2. Ask the DBA to confirm service names, certificate identities, CA, account permissions, and network ports before configuring the TLS connections below.
3. If a usable password is available, continue using it. Handle decryption, secret services, or IAM in the [host DataSource factory](database-credentials.md) when needed.
4. Run a small real DEV range and compare fields, then use [resilience testing](resilience-testing.md) to validate interruption recovery.
5. Package the same host into a container and configure networking, certificates, environment variables, and runtime identity. Local connectivity does not prove container networking works.

Driver dependencies, database login accounts, TLS trust, and cloud runtime identity are separate concerns. Adding JDBC dependencies enables protocol handling; it does not grant database permissions or trust certificates. Diagnose errors layer by layer.

## Oracle source connections

The component supplies `com.oracle.database.jdbc:ojdbc11` as a runtime dependency. Check the support matrix for your database/driver versions. Give the source account SELECT on required tables only; it does not need object creation, table updates, or source sequence NEXTVAL access.

Example environment variable (replace host, port, service name, and DN with DBA-supplied values):

```text
SOURCE_JDBC_URL=jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=oracle.example.internal)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=DEV))(SECURITY=(SSL_SERVER_DN_MATCH=YES)(SSL_SERVER_CERT_DN="CN=oracle.example.internal")))
```

Mount the CA truststore read-only into the container and point JVM `javax.net.ssl.trustStore` and `javax.net.ssl.trustStoreType` at it. Provide private keys/passwords through runtime secret management, not source code or image layers, and do not print secret JVM arguments. Mutual TLS also requires a client certificate and private key; the DBA and selected driver determine Wallet/PKCS12 configuration. Global JSSE properties affect other host TLS connections, so configure and test them in an isolated migration task.

Oracle Thin supports TCPS, truststores, and server identity matching. Use the [Oracle JDBC TLS guide](https://blogs.oracle.com/developers/ssl-connection-to-oracle-db-using-jdbc-tlsv12-jks-or-oracle-wallets-122-and-lower) and your driver's documentation for parameter details. Do not disable DN matching to accept an incorrect certificate.

## PostgreSQL target connections

The component supplies `org.postgresql:postgresql` as a runtime dependency. Reuse existing host YAML for ordinary local/DEV password authentication. Example target URL:

```text
TARGET_JDBC_URL=jdbc:postgresql://postgres.example.internal:5432/dev?sslmode=verify-full&sslrootcert=/run/certs/postgres-ca.pem
```

`verify-full` validates both the certificate chain and hostname. Mount the CA file read-only; `sslmode=require` is not a substitute for full identity verification. See [pgJDBC TLS documentation](https://jdbc.postgresql.org/documentation/ssl/).

The migration account needs INSERT on target business tables and DELETE only for explicit reimports. The audit table needs SELECT/INSERT/UPDATE and row-lock permissions. Sequence calibration needs permissions to read and set the relevant sequence. The DBA precreates business tables, constraints, the audit table, and sequences. The component creates no tables and grants no permissions automatically.

## IAM: current boundaries and integration

The component only receives host DataSources. The host owns decryption/IAM, pools, and timeouts; Oracle/PostgreSQL drivers are component dependencies. See [host DataSources and credentials](database-credentials.md) for implementation placement and dynamic tokens on new physical connections. Ordinary host beans cannot be autowired before startup; use independently initialized Java factories for pre-startup migration. Real IAM still requires cloud validation.

The ECS **task role** supplies application AWS API permissions. The **task execution role** handles image pulls, logs, and secret injection in task configuration. Do not interchange them. Follow [AWS ECS IAM roles](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/security-iam-roles.html) with least privilege; do not store long-lived access keys in images.

## Run as a one-off ECS task

This remains a regular dependency JAR with no new runner Maven submodule. The host supplies a non-web Boot entry point; see `examples/ecs/MigrationJobApplication.java`, then package the host as an executable JAR. Call MigrationRunner.migrate directly. On success, close the Runner and host-owned pools, then exit normally; exceptions propagate as a nonzero process exit. Starting an existing long-running host service does not mean a one-off ECS migration task completed.

`examples/ecs/Dockerfile` uses the host artifact host.jar; build the host first. The image and examples have not been validated through an ECS deployment.

Only one migration process may run at a time, including deployment transitions and retries. The JVM permit cannot prevent overlapping containers; deployment must ensure the previous task has exited. Recovery preserves the original job-id, full configuration, actual DataSources, and source ranges. Only JDK 17 is supported. See [audit design](audit.md) for DDL and fields.

Task definition considerations:

- Use an independent one-off task with appropriate CPU/memory, logging, task role, execution role, and read-only certificate mounts.
- The one-off task need not start a complete Spring context; load rules through Runner.configLocations("file:/run/config/migration.yml").
- The host factory reads both endpoints' URLs, usernames, passwords, or secret IDs from environment/secrets. Match its variable names; do not put them under data-migration. An IAM DataSource obtains a valid token for each new physical connection.
- Keep YAML `data-migration.job-id` (or command-line `--data-migration.job-id=...`) unchanged when ECS retries the same work. Use a new job-id with CLEAR_AND_REIMPORT only for an explicitly planned full reimport.
- Networking must resolve and reach Oracle DEV TCPS and target PostgreSQL ports. Do not use the local H2 examples' localhost addresses inside a container.
- Auditing stays in the target database, preserving checkpoints across container replacement. Do not put certificates or passwords in task logs.
- Allow a stop grace period for interruption/rollback and rehearse same-job recovery after forced termination. Assess large keyless-transaction rollback time using real data volumes.

## What to record for DEV acceptance

1. Actual driver/database versions on both sides and TLS protocol/certificate details, excluding passwords or tokens.
2. Success with correct certificates; rejection of wrong CAs, expired certificates, and incorrect server identities.
3. Field-by-field comparison of Oracle NUMBER, dates/time zones, NULL, and multi-MB BLOBs migrated to PostgreSQL.
4. Source/target disconnects, lost commit responses, and ECS task termination recovery; final counts, contents, and checkpoints without duplicates or gaps.
5. IAM connection creation, new connections after token expiry, pool expansion/recycling reconnects, and failure after IAM permission revocation.
6. Keyed batch recovery, keyless whole-table rollback, new-job reimport, non-regressing sequences, and finalization-only retries.
