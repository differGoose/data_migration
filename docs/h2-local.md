# Local H2 demo

The local examples use synthetic data only and do not access real databases. Import Maven in your IDE, select JDK 17, and use the test classpath.

## Option 1: observe migration completing before host startup

Run [BeforeStartupDemo.main](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java). It creates isolated H2 databases under target and seeds 10,000 CUSTOMER rows. The host creates two Hikari DataSources and passes them to MigrationRunner. Two migrate calls verify that the same completed job does not insert again. Only after migration succeeds and resources return does the non-web example host start, then shut down.

This runnable entry is test source, not part of the production JAR. Start here if you are new to the module, then read [real host integration](getting-started.md).

## Option 2: start two databases and inspect them with DBeaver

Run [H2Demo.main](../src/test/java/com/datamigration/demo/H2Demo.java). It prepares source/target databases without migrating. The source contains 10,000 CUSTOMER rows plus BLOB/CLOB DOCUMENTS; the target has empty business and audit tables. The console prints random ports and complete JDBC URLs. Use username sa and an empty password. Keep the process running; press Enter to stop it.

Create two H2 URL connections in DBeaver using the exact Source/Target URLs printed. Use org.h2.Driver at the Maven-managed H2 version. Do not replace only the port while keeping an incorrect database path, or open a different database through an embedded URL. Inside Docker, localhost means the container itself.

```sql
SELECT COUNT(*) FROM CUSTOMER;
SELECT ID, OCTET_LENGTH(CONTENT) FROM DOCUMENTS ORDER BY ID;
```

For a separate host, create two H2 DataSources (add the H2 driver yourself) and pass them to the Runner. Copy the data-migration section from the [H2 rules](../examples/resilience/h2.yml), add a stable job-id, and omit the test-only resilience section. Configure source/target URLs in the host factory. Alternatively, adapt the [integration template](../examples/migration.yml): change source BUSINESS.CUSTOMER to CUSTOMER and target customer_copy to CUSTOMER.

After migration, target CUSTOMER should contain 10,000 rows. Compare NAME/AMOUNT by ID; the audit summary should show committed_rows=10000 and skipped_rows=0. Repeating the same job/configuration keeps 10,000 rows. Restarting H2Demo creates a new directory rather than inheriting databases/checkpoints. Do not run mvn clean during the demo; it deletes target.

## Automated tests

```sh
mvn -B -ntp verify "-Dit.test=H2MigrationIT"
mvn -B -ntp verify "-Dit.test=H2TlsIT"
```

Tests own their databases and host DataSources; H2Demo need not be running. TLS tests generate temporary certificates. H2 results do not replace real Oracle/PostgreSQL validation.
