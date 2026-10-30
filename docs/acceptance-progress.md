# 本地实施与验收记录

以下早期记录为历史验收证据；连接配置和批量参数已进行破坏性改造，当前接入方式以 README 和 getting-started.md 为准。当前仅支持与验证 JDK 17；本文下方所有双版本、执行锁和指纹记录均为历史，不代表当前能力。

## 关键节点日志与安全失败定位（2026-10-30）

新增 com.datamigration SLF4J 日志：INFO 输出配置、端点连接、容量、表预检、恢复/跳过、数据提交与 sequence 收尾；DEBUG 输出批次细节，WARN 输出有限重试，ERROR 保留原始失败阶段。runId、任务和批次关联跨线程日志；错误仅保存安全分类、属性路径及 SQLState/厂商码，不传递驱动消息或异常堆栈给 logger。MigrationException 消息列出失败任务、状态及已有安全错误码。未改变审计结构或迁移/重试事务规则。

JDK17（Amazon Corretto 17.0.16）完整 `mvn -B -ntp clean verify` 通过：230项单元/组件测试；22项集成测试中19项执行通过、3项真实入口默认跳过。UT行覆盖1221/1239（98.55%），分支720/822（87.59%），原各85%门槛通过。新增44项日志测试覆盖未知/缺失配置、容量、两端连接及事务初始化、审计/业务表和字段、主键、并发批次失败、提交未知、二次审计失败、sequence及模拟秘密不泄漏。完整验证日志为target-logging-final17.log。

在独立JDK17进程运行BeforeStartupDemo，确认迁移完成日志早于Spring Boot启动，成功任务重复调用复用审计结果，最后正常启动宿主；日志为target-logging-startup17.log。117个本地文档链接检查通过。启动前日志后端由宿主管理，不依赖尚未应用的Boot logging.level，见 [日志与排错](logging-and-troubleshooting.md)。本轮没有连接真实Oracle/PostgreSQL数据库，既有sequence示例注释保留，未提交或推送。

## 模拟宿主手动代理与默认驱动（2026-10-30）

新增HostMigrationE2EIT，使用正式classpath:application.yml直接调用Runner，不要求e2e字段。可选手动模式以两个TCP代理分别控制源、目标；本机控制页显式执行、切断、恢复及结束。断网失败后由宿主在下一次显式执行前淘汰旧池连接，不修改引擎重试策略。源/目标实际断连、同Runner恢复及重复调用由合成H2的ManualHostRecoveryIT验证；真实入口不创建H2或数据库对象。

Oracle ojdbc11与PostgreSQL驱动改为默认runtime依赖，不再需要resilience-dev profile；JDK17下通过DriverManager自动发现两种驱动，未尝试真实连接。

JDK17的完整 `mvn -B -ntp clean verify` 通过：186项单元/组件测试；22项集成测试中19项执行通过、3项真实入口默认跳过；UT行覆盖98.67%，分支86.07%，保持各85%门槛。日志target-manual-final17.log。控制访问、运行互斥、取消清理和显式恢复已验证；真实Oracle/PostgreSQL连接参数留给使用者填写，未宣称真实数据库联调。操作见 [手动代理迁移](manual-host-migration.md)。

## 单进程协调与审计简化（当前，2026-10-29）

默认引擎使用JVM共享单许可，跨Runner、Spring Context及目标表的重叠调用立即失败。取消后等待工作线程及事务清理结束才释放许可，关闭空闲Runner不能释放其他执行者的许可。数据库执行锁、guard、执行令牌、fingerprint和端点身份计算均已删除；保留批次/汇总事务行锁、提交结果核实、有限重试和同job恢复。

有键表预算为源/目标各W+1，无键为源1/目标2；真实限定大小的Hikari池在最低预算下完成迁移，低预算提前失败。新审计不产生内部锁记录，字段与连续ID/联合键范围示例见 [审计设计](audit.md)。使用者人工准备新版审计表，不提供旧检查点转换。

本轮仅使用Amazon Corretto JDK 17.0.16执行 `mvn -B -ntp clean verify`，完整通过：183项单元/组件测试；19项集成测试中17项执行通过、2项真实配置入口默认跳过。UT行覆盖893/905（98.67%），分支618/718（86.07%），原行/分支各85%门槛均通过。日志为target-audit-final17.log，报告位于target/surefire-reports、target/failsafe-reports及target/site/jacoco-ut。

保留外部DataSource E2E方法、配置类、main/Maven入口及四类故障场景；故障宿主结束后才启动恢复宿主。实际运行E2E main成功、同job再次执行以及缺失配置返回退出码1，启动前BeforeStartupDemo也成功；ECS宿主示例按release17编译。101个本地文档链接及PostgreSQL两份DDL一致性检查通过。CI固定JDK17并删除版本矩阵。

上述接口及故障验收使用调用方准备的合成H2数据库，没有真实Oracle/PostgreSQL连接参数，因此不宣称真实数据库联调完成。以下均为历史记录，旧锁和指纹相关测试已被当前的全局互斥测试替代。

## 外部DataSource真实E2E入口（历史）（2026-10-29）

新增DatabaseE2E.run直接接收调用方DataSource及配置文件，main/ConfiguredDatabaseE2EIT从显式外部配置类取得标记Bean。测试本身不准备真实数据库，不额外要求空目标，job和模式交由正式Runner处理。外部配置故障路径覆盖四类故障、同job再次调用及非空目标的无键重导恢复；临时代理端口按真实upstream规范化身份，控制文件不复制校验SQL。

Java17.0.16与Java21.0.2的clean verify均通过：187项单元/组件测试，22项集成测试中20项执行通过、2项显式真实配置入口默认跳过。UT行覆盖98.17%，分支覆盖85.83%，保留各85%门槛。证据为target-external-e2e-final17.log及target-external-e2e-final21.log。实际main的成功、重复调用及缺失宿主失败退出也通过调用方预备的H2服务器验证；这些是本地接口契约证据，不代表真实Oracle/PostgreSQL联调。

执行方法、示例与Maven命令见 [真实数据库E2E指南](real-database-e2e.md)。

## 宿主数据源改造验证（历史）（2026-10-29）

宿主直接提供 DataSource，MigrationRunner 在不创建 Spring 容器的情况下执行；Spring 包装使用源/目标注解。每表只有 batch-size 行数参数，旧连接与启动配置不再接受，不承诺旧版未完成 job 的兼容。

最终 Java 17 clean verify 已通过：182 项单元/组件测试，16 项集成测试中15项执行通过、1项真实环境入口默认跳过，行/分支85%门槛通过。日志 target-refactor-final17.log。启动前 BeforeStartupDemo 已在真实Java17运行；ECS宿主代码按release17编译，JAR检查确认不存在已删除建池/凭据类。Java21同一轮clean verify也已通过，测试数量与Java17一致，日志target-refactor-final21.log。最终UT行覆盖98.17%，分支覆盖85.83%，均高于85%。

恢复测试仍覆盖父进程TCP代理、子进程宿主、四类精确业务提交边界、恢复后数据和审计校验；DEV契约由测试宿主注册外部DataSource，不执行DEV建表或清理。真实Oracle/PostgreSQL/IAM/ECS未联调。

## 早期验证（历史）

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

## 早期交付核对（历史）

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
