# 本地实施与验收记录

本次本地交付完成。以下证据来自当前代码、测试报告、JAR 检查和最终 clean verify；云端联调边界见文末。

## 已有验证

| 要求 | 证据 |
| --- | --- |
| 双 H2、10,000 行、YAML → Spring → 方法入口 | H2MigrationIT |
| TLS 加密迁移、拒绝不受信任证书 | H2TlsIT；真实子 JVM 负向连接验证 |
| 源 TCP 服务中断后的续传 | H2RecoveryIT(false) |
| 目标已提交但响应丢失后的续传 | H2RecoveryIT(true) |
| 进程在 COMMIT 前/后直接退出、新 JVM 恢复 | H2ProcessRecoveryIT；Runtime.halt(73)，最终核对 10,000 行 |
| 联合主键、2MB BLOB | JdbcMigrationEngineTest |
| 无主键重导失败保留原数据 | JdbcMigrationEngineTest |
| 表之间失败独立 | JdbcMigrationEngineTest |
| 并发跳过额度、序列不回退、配置指纹 | JdbcMigrationEngineTest |
| 执行锁、重叠调用、成功审计保护 | ExecutionIsolationTest |
| 锁连接丢失后的交接、旧事务排空、旧执行者拒绝 | TableLockTest；使用真实 H2 行锁与独立连接 |
| 类型精度、SQL/参数、主键编码 | JdbcValueConverterTest、SqlSupportTest、KeyCodecTest |
| 提交前重试、提交后响应丢失、提交未知、回滚失败 | TransactionRecoveryTest |
| 序列收尾失败后不重插、审计失败回滚、源仅 SELECT | TransactionRecoveryTest |
| CLOB/BLOB 空值、超限、清理失败、精确批次行数 | JdbcRowsTest |
| H2 序列边界与 PostgreSQL JDBC 契约 | DatabaseDialectTest；PostgreSQL 尚非真实数据库联调 |
| YAML 多表独立、无键最后一行失败全回滚、联合键并发、跳过额度、3MB BLOB/CLOB、新 job 重导 | H2ScenariosIT；两个 H2 TCP 数据库、真实 Spring 服务 |
| 关闭时取消、等待回滚、资源释放后恢复 | ExecutionIsolationTest.closeCancelsInFlightWorkAndWaitsForRollbackBeforeReturning |
| 本地双 H2 手动启动样例 | H2Demo.startDemoDatabases；10,000 条 CUSTOMER，另附 DOCUMENTS 大对象样例 |
| 旧协调线程清表前、清表事务内、序列收尾中失去 guard，新任务安全重导 | H2OwnershipIT 三个真实 TCP / Spring E2E 场景 |
| H2 ALTER SEQUENCE 保留事务行锁且不可回滚 | DatabaseDialectTest.h2SequenceAlterRetainsExistingTransactionLocksButCannotBeRolledBack |
| 一表失败后排队批次保持待恢复，其他表继续完成 | JdbcMigrationEngineTest.failureStopsQueuedBatchesOfThatTableButOtherTablesStillRun |

2026-10-28 10:58 的 `mvn -B -ntp clean verify` 通过：**179 项 UT、10 项 E2E，失败/错误/跳过均为 0**。UT 行覆盖 **899/917（98.04%）**、分支 **622/722（86.15%）**。JaCoCo 仅使用 Surefire 数据，未借助 E2E 达到 85% 门槛。

环境：Amazon Corretto JDK 17.0.16，Maven 3.9.6，Spring Boot BOM 4.1.1，JUnit 5.13.4，H2 2.4.240。原始证据：jdk17-verify.log、target/surefire-reports、target/failsafe-reports、target/site/jacoco-ut/index.html。构建产物为 target/data_migration-0.1.0-SNAPSHOT.jar，javap 确认 class major version=61（Java 17）。

Java 17 适配替换了 List.getFirst、Duration.isPositive 和 ExecutorService 的 try-with-resources；ExecutorScopeTest 新增正常等待与中断清理验证，原有关闭回滚、进程恢复、TLS 测试均使用真实 JDK 17 执行。CI 已配置 Java 17 / 21 矩阵，云端执行结果以 GitHub Actions 为准。

## 最终交付核对

2026-10-29 故障恢复与目录整理补充验收：JDK 21.0.2 执行 `mvn -B -ntp clean verify` 成功，190 项单元/组件测试通过，16 项集成测试中 15 项通过、1 项真实环境入口按默认配置跳过。UT 行覆盖 98.03%，分支覆盖 86.42%，保留原 85% 门槛。四个 H2 故障场景均在指定任务第 2 次业务提交边界触发，并完成续跑及再次执行校验；本地 DEV 契约验证凭据扩展与非空目标拒绝。另以配置文件列表和显式 SOURCE_OUTAGE 场景单独执行 ConfiguredResilienceIT，通过。证据为 target-resilience-completion-verify.log、target-resilience-entry-verify.log 和 target/resilience-reports。真实 Oracle/PostgreSQL 验收仍需实际环境。

测试迁移后的包名、设施和入口见 [测试索引](test-index.md)；环境示例与执行方式见 [恢复测试指南](resilience-testing.md)。

| 交付项 | 当前证据 |
| --- | --- |
| 单 Maven 模块普通 JAR、无 REST、方法始终提供 | pom.xml 的 packaging=jar；JAR 含 MigrationService 和 AutoConfiguration.imports，不含 BOOT-INF 或宿主 application.yml；自动配置测试验证默认不开连接池、方法可注入 |
| YAML 两端连接/池/策略/表配置 | MigrationProperties、MigrationConfiguration、examples/migration.yml；绑定与非法配置测试 |
| 宿主无侵入及扩展 | MigrationAutoConfigurationTest 验证已有 DataSource/事务管理器不被覆盖、自定义引擎/服务替换；README 说明实际支持的扩展点 |
| 单键/联合键持久化恢复、无键全表事务 | 引擎组件测试及 H2MigrationIT、H2RecoveryIT、H2ProcessRecoveryIT、H2ScenariosIT |
| 两级并发、失败隔离、连接预算、关闭生命周期 | 固定表/worker 线程池与预算预检；ExecutionIsolationTest、JdbcMigrationEngineTest、H2ScenariosIT |
| 审计和数据同事务、提交未知不盲目重放 | TransactionRecoveryTest、H2RecoveryIT、H2ProcessRecoveryIT；DDL 不由引擎执行 |
| 重导与序列收尾 | H2ScenariosIT、H2OwnershipIT、JdbcMigrationEngineTest、TransactionRecoveryTest、DatabaseDialectTest |
| 大对象和 NULL | 2MB 联合键 BLOB 组件测试、3MB BLOB/CLOB E2E、JdbcRowsTest 资源释放/大小限制/NULL |
| 10,000 行双 H2、可手动启动、TLS | H2Demo、TcpDatabases；H2MigrationIT 与 H2TlsIT 的可信证书迁移和不可信证书子进程拒绝 |
| 文档、DDL、ECS 接入说明 | README、architecture.md、cloud-deployment.md、examples/ecs；docs/sql 与 resources 中 PostgreSQL DDL SHA256 一致 |
| UT >=85%、E2E 通过 | 上述 clean verify 与 JaCoCo 报告 |

SQL 在清除前预检列、参数和唯一键；源保持静态、目标暂停业务写入是执行前提。序列异常属于已提交数据之后的收尾失败，按同 job-id 恢复，不将其表述为整次迁移已回滚。H2 的 PostgreSQL 方言单元契约测试不等于真实 PostgreSQL 联调。

## 明确不在本地验收中宣称完成的项目

真实 Oracle/PostgreSQL 环境、Oracle TCPS、AWS IAM、ECS 部署。
这些属于用户已同意延后的云端联调；本次仍须提供接入与部署说明。
