# 宿主从接入到完成第一次迁移

“宿主”是你自己的 Spring Boot 应用。本项目提供一个普通依赖 JAR，由宿主调用 Java 方法完成迁移。你不需要启动本项目的独立服务器，也不需要为它配置 REST 地址。

本文按 Oracle → PostgreSQL 说明。想先用合成数据试跑，请先看 [H2 本地演示](h2-local.md)。本地 H2 通过不代表真实数据库驱动已经联调通过。

## 1. 准备运行环境和依赖

当前接入基线为 Java 17 及以上、Spring Boot 4.1.1。Boot 2/3 兼容性未承诺。先在本组件仓库执行：

```sh
mvn -B -ntp clean install
```

成功后，组件安装到本机 Maven 仓库。这个命令只构建并测试组件，不会读取你的真实业务库。团队接入可将产物发布到公司 Maven 私服；GitHub 上有源码不等于 Maven Central 已有依赖。

在**宿主项目**的 pom.xml 中添加：

```xml
<dependency>
    <groupId>com.datamigration</groupId>
    <artifactId>data_migration</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

数据库驱动的版本由宿主 Boot 依赖管理提供。宿主须使用相应 BOM 或 parent；普通 Maven 项目若没有版本管理，需要自行声明兼容驱动版本。这里的数据库驱动与本仓库的 `resilience-dev` 测试 profile 是两回事，宿主实际运行时也需要自己的驱动依赖。

## 2. 先准备数据库对象

由 DBA 或你自己的建表流程完成以下准备，迁移组件不自动执行 DDL：

- 源库有可读取的业务表，源账号有查询权限。
- 目标库有对应业务表，字段类型能接收迁移值，目标账号有 INSERT 权限。
- 目标库有审计表，目标账号能 SELECT、INSERT、UPDATE 并使用行锁。
- 需要序列校准时，提前创建目标命名序列并授予相关权限。

目标 PostgreSQL 审计表结构见 [migration-audit.sql](../src/main/resources/db/postgresql/migration-audit.sql)。在目标库人工执行这份脚本；它不是源库脚本。如果放在其他 schema，请同时调整脚本的建表名和 YAML 的 `strategy.audit.table-name`。

例如源 Oracle 有 `BUSINESS.CUSTOMER(ID, NAME, AMOUNT)`，你要复制到 PostgreSQL 的 `migration_test.customer_copy`，先在目标库创建 schema 和业务表：

```sql
CREATE SCHEMA migration_test;
CREATE TABLE migration_test.customer_copy (
    id BIGINT PRIMARY KEY,
    name VARCHAR(200),
    amount NUMERIC(18, 2)
);
```

这是解释字段映射的示例结构，实际类型、长度和精度应按你的源数据设计。已有 schema 时不重复执行建 schema 语句。下例审计表使用默认 schema 下的 `migration_audit`。

## 3. 在宿主配置要迁移的数据

在宿主运行目录创建 `migration-local.yml`，在宿主 application.yml 导入：

```yaml
spring:
  config:
    import: file:./migration-local.yml
```

`./` 相对于宿主进程的工作目录，不一定是 JAR 所在目录。也可以直接把 `data-migration` 节放进宿主 application.yml。真实连接配置不要提交到公共仓库。

下面是一张表、一个 worker 的完整迁移配置：

```yaml
data-migration:
  job-id: customer-import-20261029
  run-on-startup: false
  source:
    jdbc-url: ${MIGRATION_SOURCE_URL}
    username: ${MIGRATION_SOURCE_USERNAME}
    password: ${MIGRATION_SOURCE_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
    pool:
      maximum-pool-size: 4
  target:
    jdbc-url: ${MIGRATION_TARGET_URL}
    username: ${MIGRATION_TARGET_USERNAME}
    password: ${MIGRATION_TARGET_PASSWORD}
    driver-class-name: org.postgresql.Driver
    pool:
      maximum-pool-size: 4
  strategy:
    batch-size: 200
    commit-size: 1000
    max-concurrent-tables: 1
    skip-limit: 0
    audit:
      table-name: migration_audit
  tables:
    - name: customer
      target-table: migration_test.customer_copy
      mode: APPEND_RESUME
      primary-key: [ID]
      worker-count: 1
      select-sql: SELECT ID, NAME, AMOUNT FROM BUSINESS.CUSTOMER ORDER BY ID
      insert-sql: INSERT INTO migration_test.customer_copy(id, name, amount) VALUES (?, ?, ?)
      fields:
        - {source-column: ID, target-jdbc-type: BIGINT}
        - {source-column: NAME, target-jdbc-type: VARCHAR}
        - {source-column: AMOUNT, target-jdbc-type: DECIMAL}
```

给**宿主进程**提供这些环境变量，IDE 运行时填在该运行配置的 Environment variables 中，容器运行时由部署配置注入：

| 变量 | 填什么 |
| --- | --- |
| `MIGRATION_SOURCE_URL` | Oracle JDBC URL，例如 `jdbc:oracle:thin:@//oracle.example.internal:1521/DEV` |
| `MIGRATION_SOURCE_USERNAME` / `MIGRATION_SOURCE_PASSWORD` | 源库查询账号及密码 |
| `MIGRATION_TARGET_URL` | PostgreSQL JDBC URL，例如 `jdbc:postgresql://pg.example.internal:5432/dev` |
| `MIGRATION_TARGET_USERNAME` / `MIGRATION_TARGET_PASSWORD` | 目标库迁移账号及密码 |

这些主机名是占位示例。正式 TLS 配置见 [云端连接指南](cloud-deployment.md)；加密密码或 IAM 见 [凭据扩展](database-credentials.md)。

## 4. 选择一种触发方式

引入依赖后 Spring 自动提供 `MigrationService`，无需 `@Enable...` 注解或扫描 `com.datamigration`。下面类放在**宿主应用扫描范围**内：

```java
package com.example.application;

import com.datamigration.api.MigrationResult;
import com.datamigration.api.MigrationService;
import org.springframework.stereotype.Service;

@Service
public class CustomerImportService {
    private final MigrationService migrationService;

    public CustomerImportService(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    public MigrationResult importCustomers() {
        return migrationService.migrate();
    }
}
```

这个示例只是提供一个业务方法，宿主仍需从自己的流程调用 `importCustomers()`。组件没有自带 HTTP 接口或定时任务。方法会阻塞调用线程，直到全部表任务结束；大迁移应安排在合适的任务执行流程中。

如果这是专门的一次性迁移应用，可以把 `run-on-startup` 改为 `true`，由启动过程同步执行，不再另写一次手动调用。成功后是否退出进程由宿主决定，见 [ECS 一次性任务示例](../examples/ecs/MigrationJobApplication.java)。多实例服务不宜每个实例都启用启动迁移。

迁移独立管理自己的数据库连接与事务。在宿主业务方法外层加 `@Transactional`，不会让两端迁移和宿主业务变成同一个全局事务。

## 5. 判断结果

正常返回 `MigrationResult`，其 `tables()` 中有每张表的 `taskName()`、`status()`、`committedRows()`、`skippedRows()`、`retries()` 和脱敏错误分类。

```java
try {
    var result = migrationService.migrate();
    for (var table : result.tables()) {
        System.out.println(table.taskName() + ": " + table.status()
                + ", committed=" + table.committedRows()
                + ", skipped=" + table.skippedRows());
    }
} catch (com.datamigration.api.MigrationException failure) {
    // failure.result().tables() 可查看各表结果和已提交进度。
    // 将失败交给宿主任务系统处理，不要当作本轮成功。
    throw failure;
}
```

配置错误也可能直接抛出其他运行时异常，不应仅把 MigrationException 以外的异常视为成功。不要输出完整配置、密码或业务行。

第一次小范围试跑后，在两端人工比对行数、主键及关键字段；开启坏数据跳过时尤其要核对 `skippedRows`。API 成功不表示业务字段已获得你所要求的全部人工验收。

## 6. 故障恢复如何操作

源数据保持不变，修复网络或权限后，用**同一个 job-id、任务 name 和原迁移配置**重新启动宿主并调用同一方法。有键表继续未完成批次，成功批次不会重插。不要为了消除错误换 job-id，也不要手动删除审计记录。

首次调用成功绑定后的配置会缓存在当前 Spring Context 中，修改 YAML 后应重启宿主，不能靠再次调用方法热加载新配置。已完成任务同 job-id 再执行会返回已有进度；它不是定时增量同步功能。

需要从零重做时，先阅读 [恢复与运维指南](operations.md)，明确新 job-id 和目标数据处置方式。默认追加模式不自动清理已有目标数据，也不自动 upsert。
