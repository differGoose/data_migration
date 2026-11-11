# Host integration: complete migration before application startup

This module performs migration only. The host owns database addresses, accounts, decryption, IAM, and connection pools; the component supplies Oracle/PostgreSQL runtime driver dependencies. Invoke migration before the real host's `SpringApplication.run`.

## 1. Add the dependency and prepare database objects

Run `mvn clean install` in the component repository, then add this to the host pom.xml:

```xml
<dependency>
  <groupId>com.datamigration</groupId>
  <artifactId>data_migration</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use Boot 4.1.1 dependency management and JDK 17. Oracle/PostgreSQL JDBC drivers are already runtime dependencies; add drivers yourself for other databases. For Hikari, separately add `com.zaxxer:HikariCP`; the component does not supply it as a production dependency. Grant source business-table read access only. The target account needs business INSERT, audit read/write, and row-lock permissions. Add permissions for reimport or sequence calibration only when required.

Create target and audit tables manually. Apply the [PostgreSQL audit SQL](../src/main/resources/db/postgresql/migration-audit.sql) to the target database. Choose field types and lengths based on source data, for example:

```sql
CREATE TABLE customer_copy (
    id BIGINT PRIMARY KEY,
    name VARCHAR(200),
    amount NUMERIC(18,2)
);
```

Keep source data unchanged throughout migration and recovery, and pause target business writes. Migration creates no consistent snapshot and provides no CDC.

## 2. Define migration rules

Copy the [complete template](../examples/migration.yml) to migration.yml on the host classpath and replace table names and SQL. Connection settings do not belong under data-migration.

```yaml
data-migration:
  job-id: customer-import
  strategy:
    max-concurrent-tables: 1
    audit:
      table-name: migration_audit
  tables:
    - name: customer
      batch-size: 1000
      target-table: customer_copy
      primary-key: [ID]
      worker-count: 1
      select-sql: SELECT ID, NAME, AMOUNT FROM BUSINESS.CUSTOMER ORDER BY ID
      insert-sql: INSERT INTO customer_copy(id, name, amount) VALUES (?, ?, ?)
      fields:
        - {source-column: ID, target-jdbc-type: BIGINT}
        - {source-column: NAME, target-jdbc-type: VARCHAR}
        - {source-column: AMOUNT, target-jdbc-type: DECIMAL}
```

fields order matches INSERT question marks; source-column matches SELECT result labels. batch-size is the source-row limit for each transaction block, not a limit of 1000 rows per API invocation.

## 3. Create host DataSources

The following code belongs to the host project; replace it with your existing factories if available. Add Hikari yourself; Oracle/PostgreSQL drivers are already supplied:

```java
import com.zaxxer.hikari.HikariDataSource;

public final class HostDataSources {
    public static HikariDataSource createSource() {
        return create("SOURCE");
    }
    public static HikariDataSource createTarget() {
        return create("TARGET");
    }
    private static HikariDataSource create(String side) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(System.getenv(side + "_JDBC_URL"));
        ds.setUsername(System.getenv(side + "_USERNAME"));
        ds.setPassword(System.getenv(side + "_PASSWORD"));
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(10000);
        return ds;
    }
}
```

This is a minimal host DataSource example. Existing host DataSources can be passed directly. Authentication, TLS, pools, and timeouts are host responsibilities. See [DataSource ownership](database-credentials.md).

## 4. Migrate first, then start the host in main

```java
import com.datamigration.api.MigrationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        try (var source = HostDataSources.createSource();
             var target = HostDataSources.createTarget();
             var migration = MigrationRunner.builder()
                 .source(source)
                 .target(target)
                 .configLocations("classpath:migration.yml")
                 .arguments(args)
                 .build()) {
            migration.migrate();
        }
        SpringApplication.run(Application.class, args);
    }
}
```

The final line runs only after migration succeeds. No temporary Spring context is created, and business beans/web services start afterward. Capacity inspection reads HikariCP maximumPoolSize and validates configured table/worker concurrency without creating or resizing pools. Insufficient capacity identifies source/target, required demand, and maximumPoolSize. See [connection budgets](configuration.md).

These pools are migration-specific resources in this example, so the host closes them with try-with-resources. If shared pools are needed by the host afterward, keep their lifecycle under host ownership and close only the Runner. The component never closes a supplied DataSource.

configLocations accepts multiple locations; later files override earlier ones. file: paths are supported relative to the process working directory. Explicit command-line arguments take precedence, for example `--data-migration.job-id=customer-import`. Boot imports, environment variables, and profiles are supported without creating a bean context. Do not combine environment with configLocations/arguments on the same builder.

## 5. Migration after the context starts

Place both beans within the host's scan scope or @Import this configuration:

```java
import com.datamigration.api.MigrationSource;
import com.datamigration.api.MigrationTarget;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationDataSourcesConfiguration {
    @Bean(destroyMethod = "close")
    @MigrationSource
    public DataSource source() { return HostDataSources.createSource(); }

    @Bean(destroyMethod = "close")
    @MigrationTarget
    public DataSource target() { return HostDataSources.createTarget(); }
}
```

Import migration rules in the host application.yml:

```yaml
spring:
  config:
    import: classpath:migration.yml
```

Inject `com.datamigration.api.MigrationService` and explicitly call `migrate()`. Each role must be unique; unmarked host DataSources are not selected. Implementation types may also carry annotations, but @Bean methods should declare recognizable DataSource types.

**Annotations cannot make ordinary host beans exist before .run().** For pre-startup migration use the direct-object entry in step 4. The component registers no ApplicationRunner and does not automatically migrate again after startup.

## 6. Repeated calls and restarts

Every call on the same Runner after an attempt finishes re-reads target auditing. Successful tables skip, failed tables resume unfinished blocks, and data-complete tasks continue verification/finalization. Preserve job-id, task name, actual DataSources, and migration rules. Recovery requires no YAML change.

Configuration is cached after the first successful binding, with no hot reload. Later additions to the source database are not automatically synchronized by repeated calls. Changing batch-size or other rules and resuming the same job is unsupported; the component computes no digest to detect changes.

Both methods return MigrationExecution. A new migrate() attempt waits and propagates failures. migrateAsync() reports completion or failure through completion(). Running cross-method duplicates return the same execution immediately, with progress() snapshots. After completion, the next call creates a new execution and reads auditing again. See [host execution](host-execution.md) and [operations](operations.md).

A runnable example is [BeforeStartupDemo](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java), using isolated H2 synthetic data without real accounts.

Duplicates on one instance share its running execution. Different Runners/Spring contexts cannot execute overlapping migrations in the same JVM. One migration still supports table and batch concurrency. Deployment must ensure a single migration process at a time. Only JDK 17 is supported and verified. See [audit design](audit.md).
