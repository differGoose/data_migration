# data_migration

可嵌入宿主的 Java 17 / Spring Boot 4.1.1 JDBC 迁移组件。宿主提供源、目标 `DataSource`，组件负责表映射、事务、审计及断点恢复。当前为 `0.1.0-SNAPSHOT` 开发版本。

## 从哪里开始

| 需要做什么 | 文档 |
| --- | --- |
| 在宿主 `.run()` 前迁移，或通过注解接入 Spring | [完整接入步骤](docs/getting-started.md) |
| 理解每表 batch-size、job-id 和字段映射 | [配置说明](docs/configuration.md) |
| 运行合成数据示例 | [H2 本地指南](docs/h2-local.md) |
| 数据源归属、容量与生命周期 | [宿主数据源边界](docs/database-credentials.md) |
| 查询进度、失败续跑、重新全量迁移 | [运维指南](docs/operations.md) |
| 宿主运行与部署边界 | [运行边界](docs/cloud-deployment.md) |
| 把真实DataSource直接交给E2E，或通过配置main/Maven执行 | [真实数据库E2E](docs/real-database-e2e.md) |
| 模拟断网和宿主崩溃 | [恢复测试](docs/resilience-testing.md) |
| 手动断开/恢复source或target，再调用原job续跑 | [模拟宿主手动代理测试](docs/manual-host-migration.md) |
| 同步/异步调用、防重复和进度 | [宿主执行入口](docs/host-execution.md) |
| 迁移失败定位与启动前日志 | [日志与排错](docs/logging-and-troubleshooting.md) |
| 实现原理与测试位置 | [架构](docs/architecture.md)、[测试索引](docs/test-index.md) |

## 启动前执行

先在本仓库 `mvn clean install`，宿主引入 `com.datamigration:data_migration:0.1.0-SNAPSHOT`，自行添加连接池；Oracle和PostgreSQL JDBC驱动已作为运行时依赖提供。组件没有内置数据库连接配置或连接池，不创建 Spring 容器。

```java
public static void main(String[] args) {
    // HostDataSources 是宿主自己的工厂，返回可关闭的连接池。
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

迁移异常向外传播，正式宿主不会启动。`4` 表示宿主为迁移预留的连接数，不会修改连接池容量；源、目标分别声明。Runner 关闭只释放自己的执行资源，不关闭宿主数据源。

YAML 只描述迁移工作，完整例子见 [migration.yml](examples/migration.yml)。每张表使用独立 `tables[].batch-size`，默认 1000：有键表按该数量划分事务块、设置 fetch 提示及 JDBC batch 上限，单次调用持续迁移至完成。无键表仍为整表事务。

同一 Runner/单例 Service 的 migrate() 与 migrateAsync() 共用防重复状态，运行中重复调用立即返回同一 MigrationExecution，可从 progress() 读取进度；成功后返回缓存句柄，失败后显式调用按审计续跑。同步首次调用等待完成，异步首次调用立即返回。不同 Runner / Spring Context 的执行仍受 JVM 全局互斥保护，冲突时失败。详见 [宿主执行入口](docs/host-execution.md)。迁移内部仍支持多表和批次并发。许可在事务和工作线程清理完毕后释放；部署方必须保证不同进程不会同时迁移。

## 重复调用与恢复

同一 job-id 表示同一轮工作，其中各表以 name 区分。重复调用会跳过成功任务，继续未完成批次，或重试未完成收尾；不是定时增量同步。源数据在迁移及恢复期间保持不变。恢复必须保持配置、数据源和源数据范围不变；组件不计算配置摘要，不检测配置漂移。

目标业务表、审计表及所需序列必须人工创建。默认 APPEND_RESUME 不清理目标、不自动 upsert；CLEAR_AND_REIMPORT 显式允许删除整张目标表。审计字段和范围恢复示例见 [审计设计](docs/audit.md)。审计 SQL：[PostgreSQL](src/main/resources/db/postgresql/migration-audit.sql)、[H2](src/main/resources/db/h2/migration-audit.sql)。

## Spring 容器内调用

宿主用 `@MigrationSource(capacity=4)`、`@MigrationTarget(capacity=4)` 标记两个 DataSource 的 @Bean 方法或实现类型，再注入 `MigrationService` 手动调用。每端必须唯一，不会因为启动容器就自动迁移。`.run()` 之前 Bean 尚不存在，请用上面的直接传对象入口。

## 构建与边界

```sh
mvn -B -ntp clean verify
```

Surefire 运行 `*Test`，Failsafe 运行本地 `*IT`；真实 DEV 需显式启用。JaCoCo 单独统计 Surefire，行/分支均至少 85%。本地和 CI 仅支持、验证 JDK 17。组件为普通依赖 JAR，不是独立可执行服务；当前不承诺 Boot 2/3 兼容。

本次为破坏性改造，不兼容旧连接配置、旧批次参数和旧版本未完成检查点。Hikari 和 H2 仅用于测试/演示，不作为组件的宿主运行依赖。真实 Oracle/PostgreSQL 数据源与业务数据需由使用者在实际环境验收；认证、TLS及部署由宿主管理。

贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，变化见 [CHANGELOG.md](CHANGELOG.md)。
