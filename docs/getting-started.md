# 宿主接入：在应用启动前完成迁移

本模块只执行迁移。数据库地址、账号、解密、IAM与连接池由宿主负责；组件提供Oracle/PostgreSQL运行时驱动依赖。先调用迁移，再执行正式宿主的 `SpringApplication.run`。

## 1. 引入依赖并准备对象

在组件仓库执行 `mvn clean install`，宿主 pom.xml 添加：

```xml
<dependency>
  <groupId>com.datamigration</groupId>
  <artifactId>data_migration</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

宿主使用 Boot 4.1.1 依赖管理、JDK 17，Oracle/PostgreSQL JDBC驱动已由组件运行时依赖提供；其他数据库需自行添加驱动。若用 Hikari，宿主另行添加 `com.zaxxer:HikariCP`；组件不会传递这个生产依赖。源账号只读业务数据，目标账号具备业务 INSERT、审计读写和锁权限。需要重导或序列校准时再授予对应权限。

目标表与审计表人工创建。[PostgreSQL 审计 SQL](../src/main/resources/db/postgresql/migration-audit.sql) 在目标库执行。字段类型和长度按源数据设计，例如：

```sql
CREATE TABLE customer_copy (
    id BIGINT PRIMARY KEY,
    name VARCHAR(200),
    amount NUMERIC(18,2)
);
```

源数据在迁移和恢复期间不变；目标暂停业务写入。迁移不创建一致快照，不提供 CDC。

## 2. 填迁移规则

将 [完整模板](../examples/migration.yml) 复制为宿主 classpath 中的 migration.yml，替换表名与 SQL。连接信息不放在 data-migration 下。

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

fields 顺序对应 INSERT 的问号；source-column 对应 SELECT 列标签。batch-size 是每个事务块的源记录数量上限，不是每次 API 调用只处理 1000 行。

## 3. 宿主创建 DataSource

下面代码属于宿主项目，可用你们已有的数据源工厂替代。需自行引入 Hikari；Oracle/PostgreSQL驱动已提供：

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

这六个环境变量由宿主运行环境提供。密码需要解密时，在这个工厂里解密；IAM 应通过宿主支持动态 token 的 DataSource 获取新连接，不应把一次生成的 token 永久当作池密码。详见 [数据源与凭据](database-credentials.md)。

## 4. 在 main 里先迁移，再启动

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
                 .source(source, 4)
                 .target(target, 4)
                 .configLocations("classpath:migration.yml")
                 .arguments(args)
                 .build()) {
            migration.migrate();
        }
        SpringApplication.run(Application.class, args);
    }
}
```

只有迁移成功才执行最后一行。这里没有启动临时 Spring 容器，宿主业务 Bean/Web 服务均在迁移后启动。Builder 的容量声明不创建或调整池；4 个连接足以运行一张单 worker 有键表。多表并发需按 [连接预算](configuration.md)计算。

源/目标池在本例属于迁移专用资源，所以由宿主的 try-with-resources 关闭；如果是随后宿主仍要使用的共享池，应由宿主保留其生命周期，只关闭 Runner。组件永远不会关闭传入的 DataSource。

configLocations 接受多个位置，后文件覆盖前文件；也支持 file: 路径（相对进程工作目录）。显式命令行参数优先，例如 `--data-migration.job-id=customer-import`。支持 Boot 的配置导入、环境变量和 profile；不创建 Bean 容器。不要向同一个 builder 同时传 environment 与 configLocations/arguments。

## 5. 如果迁移发生在容器启动后

两个 Bean 放在宿主扫描范围内，或使用 @Import 导入该配置类：

```java
import com.datamigration.api.MigrationSource;
import com.datamigration.api.MigrationTarget;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationDataSourcesConfiguration {
    @Bean(destroyMethod = "close")
    @MigrationSource(capacity = 4)
    public DataSource source() { return HostDataSources.createSource(); }

    @Bean(destroyMethod = "close")
    @MigrationTarget(capacity = 4)
    public DataSource target() { return HostDataSources.createTarget(); }
}
```

在宿主 application.yml 导入迁移规则：

```yaml
spring:
  config:
    import: classpath:migration.yml
```

然后注入 `com.datamigration.api.MigrationService`，手动调用 `migrate()`。每端标记必须唯一，其他不带标记的宿主 DataSource 不会被选中。实例类型也可标注，但 @Bean 方法应声明可识别的 DataSource 类型。

**注解方式不能让普通宿主 Bean 在 .run() 前存在。** 要启动前迁移，请使用第 4 步的直接传对象方式；组件不注册 ApplicationRunner，不会启动后自动再跑一次。

## 6. 再次调用和重启

同一个 Runner 重复 migrate，或重启后以同配置新建 Runner：成功表跳过，失败表恢复未完成块，序列收尾失败只重试收尾。保持 job-id、任务 name、实际数据源及全部迁移配置不变，不需要修改 YAML 来表示“我要恢复”。

配置首次绑定成功后缓存，不热更新。源库后来新增数据，也不会因为重复调用就自动同步。同 job 恢复不支持修改 batch-size 等迁移规则，组件不计算配置摘要来检测变化。

方法同步返回 MigrationResult；有表失败时抛 MigrationException，通过 result().tables() 查看各表 committedRows、skippedRows、status 与脱敏错误。配置或数据源初始化错误可能抛其他运行时异常，也应阻止宿主启动。完整处理见 [运维指南](operations.md)。

可运行实例位于 [BeforeStartupDemo](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java)，使用独立 H2 合成数据，无需真实账号。

同一 JVM 仅允许一次迁移执行，不同 Runner / Spring Context 的重叠调用立即失败；单次执行内部仍可多表、批次并发。部署方保证任意时刻只有一个迁移进程。仅支持和验证 JDK 17。审计结构与恢复边界见 [审计设计](audit.md)。
