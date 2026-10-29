# Real database E2E with your own DataSources

Tests accept your usable source/target DataSources, load migration rules, call the production `MigrationRunner`, and validate data/auditing. They do not create real databases, seed data, or implement DataSource creation. main/Maven explicitly fail without external DataSource configuration; there is no H2 fallback.

These methods are test fixtures under this repository's `src/test/java`, not part of the production JAR. Run with this repository's test classpath or include the relevant fixtures in your host test sources. Independent local H2 tests remain available alongside real database testing.

## Option 1: pass two existing objects directly

```java
import com.datamigration.support.DatabaseE2E;

var report = DatabaseE2E.run(
    sourceDataSource, 4,
    targetDataSource, 4,
    "file:./e2e-local.yml"
);
System.out.println(report.directory());
```

The two 4 values declare available migration connection capacities; they do not resize pools. The method only borrows/returns connections and does not close either DataSource. The caller retains creation and shutdown ownership.

Copy the [real migration template](../examples/e2e/oracle-postgresql.yml) to e2e-local.yml and replace tables, fields, job-id, and query ranges. For direct objects, remove e2e.configuration-classes (no configuration class is needed). Connection addresses and accounts need not be passed separately to this method.

The method runs migration -> validation -> same-job/same-configuration migration again -> validation that data/audit totals did not accumulate. A normal return contains results, audit counts, and a report directory. MigrationException propagates on migration failure; validation failures throw assertion errors and preserve results. Fix the cause and call the same method/configuration to recover.

## Two configuration sections

`data-migration` remains production migration configuration with the same job-id, table, batch-size, and mode semantics.

```yaml
e2e:
  scenario: MIGRATION
  phase-timeout: 180s
  report-directory: target/e2e-reports
  validation:
    customer:  # Must match tables[].name.
      source-sql: SELECT ID, NAME, AMOUNT FROM BUSINESS.CUSTOMER ORDER BY ID
      target-sql: SELECT ID, NAME, AMOUNT FROM MIGRATION_TEST.CUSTOMER_COPY ORDER BY ID
```

source-sql describes the **expected final data**; target-sql selects the corresponding target range. Both use fields order and the same deterministic unique-key ordering. Tests stream counts/field digests without saving business rows. If skips are allowed, exclude expected skipped rows from source-sql while retaining the actual range in migration select-sql. Audit validation uses the full migration range to verify committed+skipped and successful batch totals.

Tests add no empty-target requirement, mandatory append mode, or skip-limit=0 restriction. New/repeated jobs, key conflicts, checkpoints, and explicit CLEAR_AND_REIMPORT follow the production engine. That mode already DELETEs the entire target table; the test performs no additional cleanup outside the engine. Source data must remain stable during execution and validation.

phase-timeout bounds ordinary E2E execution and cancellation requests; queries also use JDBC timeouts. The caller still must configure finite connection/network-read timeouts on DataSources. Java cannot safely kill a driver call that ignores interruption, so cleanup may wait for the driver to return.

## Simulate only the host call: production configuration without extra E2E validation

Open [HostMigrationE2EIT](../src/test/java/com/datamigration/e2e/HostMigrationE2EIT.java). This test simulates host creation of two real connection pools and directly calls production MigrationRunner.migrate. It does not call DatabaseE2E and requires no e2e section, validation SQL, or external Spring configuration class.

Source/target JDBC URLs, usernames, and passwords are blank. Fill six constants locally and do not commit real details. Put production configuration in src/test/resources/application.yml; the test loads classpath:application.yml. Only data-migration rules for job-id, mappings, and strategies are required, using the [production template](../examples/migration.yml). Change CONFIG_LOCATION for another filename. Users prepare business/audit tables and source data; writes and explicit reimports retain production engine behavior.

```powershell
mvn -B -ntp test-compile failsafe:integration-test failsafe:verify "-Dit.test=HostMigrationE2EIT" "-Dhost.e2e.enabled=true"
```

In the IDE, add VM argument `-Dhost.e2e.enabled=true` to the JUnit run configuration and run the method. Real database execution is skipped by default. Explicit opt-in with missing connection settings fails, with no H2 fallback. Each pool has four connections; adjust capacity constants if migration needs more. The host configures driver connect/read timeouts. In ordinary mode, migration exceptions fail the test directly. There is no extra content comparison or automatic repetition; repeated runs follow production job-id recovery semantics.

DatabaseE2E.run above and ConfiguredDatabaseE2EIT below remain additional validation entries that require e2e configuration. Use HostMigrationE2EIT from this section to simulate only a real host call.

To manually cut/restore source or target during migration, also set `-Dhost.e2e.manual=true` on the same test and use the local control page. Only production migration configuration is needed, with no e2e fields. See [manual proxy migration](manual-host-migration.md) for addresses, URL templates, and complete steps.

## Option 2: run main or Maven

A separate process cannot access Java objects in your current JVM. Supply a Spring configuration class that only registers source/target DataSources, exposing existing DataSources without implementing a new interface. See the [configuration template](../examples/e2e/ExternalDataSourcesConfiguration.java) and replace YourExistingDataSources. Each role must have exactly one bean marked `@MigrationSource(capacity=...)` or `@MigrationTarget(capacity=...)`; unrelated DataSources are ignored.

Put the configuration class on the test classpath; do not pass the entire business application entry class. Tests load only explicitly named classes and do not import the H2 host by default. The test-host context manages bean destruction according to your declaration. For borrowed shared objects, use `@Bean(destroyMethod="")` to disable Spring's inferred close, as in the template. If that test host creates and owns pools, explicitly use `destroyMethod="close"` to release them.

```yaml
e2e:
  configuration-classes: [your.project.ExternalDataSourcesConfiguration]
```

You may use E2E_DATASOURCE_CONFIG_CLASS from the template. The template also references E2E_JOB_ID, E2E_ID_MIN, and E2E_ID_MAX. Prepare real target business/audit tables and permissions in advance.

Run from the repository root (preserve quoting in PowerShell):

```powershell
mvn -B -ntp verify "-Dit.test=ConfiguredDatabaseE2EIT" "-De2e.config=file:./e2e-local.yml"
```

Oracle/PostgreSQL drivers are included by default, with no extra Maven profile. Tests do not prepare databases. Default `mvn clean verify` skips this entry and does not connect to real databases.

In the IDE, run `com.datamigration.support.DatabaseE2E.main` with one or more configuration locations as program arguments, for example:

```text
file:./e2e-local.yml
```

Or launch the same main through Java in PowerShell:

```powershell
mvn -B -ntp test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt" "-DincludeScope=test"
$testClasspath = "target/test-classes;target/classes;" + (Get-Content target/test-classpath.txt -Raw).Trim()
java -cp $testClasspath com.datamigration.support.DatabaseE2E file:./e2e-local.yml
```

Java methods and main accept multiple locations, with later files overriding earlier ones. Maven e2e.config uses comma-separated locations. Spring replaces lists as a whole rather than merging tasks by name. main returns a nonzero exit code on failure and prints only a redacted failure message.

## Ordinary migration and fault scenarios

scenario supports MIGRATION, SOURCE_OUTAGE, TARGET_OUTAGE, APP_BEFORE_COMMIT, and APP_AFTER_COMMIT. Fault scenarios use configured main/Maven entries, not current-JVM DataSource objects passed into child processes. They reuse parent TCP proxies and independent host JVMs. Each child gets DataSources from external configuration and executes through the production Runner.

Load the [fault configuration](../examples/e2e/fault.yml) as the second file:

```powershell
mvn -B -ntp verify "-Dit.test=ConfiguredDatabaseE2EIT" "-De2e.config=file:./e2e-local.yml,file:examples/e2e/fault.yml"
```

Configure real upstream hosts/ports for both endpoints. Your DataSources must route actual connections through local proxy ports. The parent allocates `e2e.source-proxy.port` / `e2e.target-proxy.port` before DataSource creation; your class can read them from Environment. Prefer @Lazy beans to avoid connections while previewing scenario settings. Tests do not rewrite DataSource connection addresses for you.

Tests verify both endpoints actually use the proxies before injecting outages. Proxy ports are routing details only, not identity digests. Recovery children start only after faulted children fully exit. Cross-process parallel takeover is not tested.

The trigger task needs a single worker for deterministic commit ordering. trigger-commit is the business commit number in this execution, excluding audit commits. Partial results continue remaining work without resetting the job. A successful trigger task goes straight to recovery/repetition validation, reporting faultTriggered=false and alreadyCompleted=true. If remaining work completes before the trigger, the report also honestly states no fault was injected rather than claiming fault coverage.

## Reports and acceptance boundaries

Each invocation creates a separate run directory. manifest.properties and result.properties record only run IDs, job, scenario, status, counts, and validation outcomes, not SQL, passwords, or business rows. Child logs and runtime.properties are private local control material containing necessary routing details; do not publicly commit them. Runtime SQL adaptation stays in memory, not control files.

Default local checks use caller-prepared H2 objects/servers to validate API contracts and four fault types, not real Oracle/PostgreSQL integration. Actual acceptance requires your usable DataSources and stable source range.

Execution and recovery both use JDK 17. Preserve migration rules, actual DataSources, and source ranges for the same job. The ordinary direct-object entry also obeys the JVM-wide permit. See [audit design](audit.md) for the schema.
