# 测试目录索引

统一源码根 `src/test/java`。Surefire 发现 `*Test`，Failsafe 发现 `*IT`；行/分支覆盖率门槛均85%。旧凭据提供器测试已由宿主 DataSource 选择、连接事务与 Runner 测试替代。

| 文件 | Java package | 用途 |
| --- | --- | --- |
| [component/DatabaseDialectTest.java](../src/test/java/com/datamigration/component/DatabaseDialectTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/DataSourceConnectionsTest.java](../src/test/java/com/datamigration/component/DataSourceConnectionsTest.java) | `com.datamigration.component` | 借用归还、回滚失败、错误脱敏。 |
| [component/ExampleConfigurationTest.java](../src/test/java/com/datamigration/component/ExampleConfigurationTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/ExecutionIsolationTest.java](../src/test/java/com/datamigration/component/ExecutionIsolationTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/ExecutorScopeTest.java](../src/test/java/com/datamigration/component/ExecutorScopeTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/JdbcMigrationEngineTest.java](../src/test/java/com/datamigration/component/JdbcMigrationEngineTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/JdbcRowsTest.java](../src/test/java/com/datamigration/component/JdbcRowsTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/MigrationAutoConfigurationTest.java](../src/test/java/com/datamigration/component/MigrationAutoConfigurationTest.java) | `com.datamigration.component` | 源/目标注解选择、容量声明与宿主资源隔离。 |
| [component/MigrationRunnerTest.java](../src/test/java/com/datamigration/component/MigrationRunnerTest.java) | `com.datamigration.component` | 启动前入口、每表边界、重试续传、配置缓存与生命周期。 |
| [component/MigrationStartupTest.java](../src/test/java/com/datamigration/component/MigrationStartupTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/ResilienceBoundaryTest.java](../src/test/java/com/datamigration/component/ResilienceBoundaryTest.java) | `com.datamigration.support` | 访问测试设施包内 JDBC 装饰器。 |
| [component/ResilienceConfigurationTest.java](../src/test/java/com/datamigration/component/ResilienceConfigurationTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/RowDigestTest.java](../src/test/java/com/datamigration/component/RowDigestTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/TcpFaultProxyTest.java](../src/test/java/com/datamigration/component/TcpFaultProxyTest.java) | `com.datamigration.component` | 组件契约。 |
| [component/TransactionRecoveryTest.java](../src/test/java/com/datamigration/component/TransactionRecoveryTest.java) | `com.datamigration.component` | 组件契约。 |
| [demo/BeforeStartupDemo.java](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java) | `com.datamigration.demo` | 可直接运行的 H2 启动前迁移示例。 |
| [demo/H2Demo.java](../src/test/java/com/datamigration/demo/H2Demo.java) | `com.datamigration.demo` | 手动入口，不参与测试发现。 |
| [e2e/H2MigrationIT.java](../src/test/java/com/datamigration/e2e/H2MigrationIT.java) | `com.datamigration.e2e` | 完整迁移与类型。 |
| [e2e/H2ScenariosIT.java](../src/test/java/com/datamigration/e2e/H2ScenariosIT.java) | `com.datamigration.e2e` | 完整迁移与类型。 |
| [resilience/ConfiguredResilienceIT.java](../src/test/java/com/datamigration/resilience/ConfiguredResilienceIT.java) | `com.datamigration.resilience` | 断网、崩溃和恢复。 |
| [resilience/DevHarnessContractIT.java](../src/test/java/com/datamigration/resilience/DevHarnessContractIT.java) | `com.datamigration.resilience` | 用人工预备H2验证DEV宿主DataSource扩展，不代表真实数据库验收。 |
| [resilience/H2ProcessRecoveryIT.java](../src/test/java/com/datamigration/resilience/H2ProcessRecoveryIT.java) | `com.datamigration.resilience` | 断网、崩溃和恢复。 |
| [resilience/H2RecoveryIT.java](../src/test/java/com/datamigration/resilience/H2RecoveryIT.java) | `com.datamigration.resilience` | 断网、崩溃和恢复。 |
| [resilience/H2ResilienceIT.java](../src/test/java/com/datamigration/resilience/H2ResilienceIT.java) | `com.datamigration.resilience` | 断网、崩溃和恢复。 |
| [support/DatabaseFixture.java](../src/test/java/com/datamigration/support/DatabaseFixture.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/H2RecoveryWorker.java](../src/test/java/com/datamigration/support/H2RecoveryWorker.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/HostDataSources.java](../src/test/java/com/datamigration/support/HostDataSources.java) | `com.datamigration.support` | 测试宿主自建 Hikari 池，不是组件连接配置。 |
| [support/MigrationAssertions.java](../src/test/java/com/datamigration/support/MigrationAssertions.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/ResilienceContexts.java](../src/test/java/com/datamigration/support/ResilienceContexts.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/ResilienceHost.java](../src/test/java/com/datamigration/support/ResilienceHost.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/ResilienceSettings.java](../src/test/java/com/datamigration/support/ResilienceSettings.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/ResilienceSuite.java](../src/test/java/com/datamigration/support/ResilienceSuite.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/ResilienceWorker.java](../src/test/java/com/datamigration/support/ResilienceWorker.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/RowDigest.java](../src/test/java/com/datamigration/support/RowDigest.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/TcpDatabases.java](../src/test/java/com/datamigration/support/TcpDatabases.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [support/TcpFaultProxy.java](../src/test/java/com/datamigration/support/TcpFaultProxy.java) | `com.datamigration.support` | 数据库、宿主、代理、控制和数据校验设施。 |
| [unit/JdbcValueConverterTest.java](../src/test/java/com/datamigration/unit/JdbcValueConverterTest.java) | `com.datamigration.unit` | 独立逻辑。 |
| [unit/KeyCodecTest.java](../src/test/java/com/datamigration/unit/KeyCodecTest.java) | `com.datamigration.unit` | 独立逻辑。 |
| [unit/SqlErrorsTest.java](../src/test/java/com/datamigration/unit/SqlErrorsTest.java) | `com.datamigration.unit` | 独立逻辑。 |
| [unit/SqlSupportTest.java](../src/test/java/com/datamigration/unit/SqlSupportTest.java) | `com.datamigration.sql` | 访问生产包内 tokens 方法，保留原 package。 |

新增外部E2E入口及验证设施：

- `support/DatabaseE2E`：直接对象方法、main与配置入口，普通场景调用正式Runner。
- `support/E2EHost` / `E2ESettings` / `E2EValidation`：外部Bean选择、场景配置、数据及审计校验。
- `e2e/ConfiguredDatabaseE2EIT`：默认跳过，真实配置显式启用。
- `component/ExternalDatabaseE2ETest`：调用方准备H2验证对象所有权、续跑、新job、跳过子集及超时。
- `e2e/ExternalFaultE2EIT`：调用方准备H2服务器，通过外部配置类验证四种故障、重复调用及无键重导恢复。

- [component/GlobalMigrationExecutionTest.java](../src/test/java/com/datamigration/component/GlobalMigrationExecutionTest.java)：跨 Spring Context 全局互斥、取消后清理等待、关闭空闲 Runner、最低连接预算。

- [e2e/HostMigrationE2EIT.java](../src/test/java/com/datamigration/e2e/HostMigrationE2EIT.java)：模拟宿主创建真实数据库DataSource并直接调用MigrationRunner；使用正式application.yml，无需e2e配置或校验SQL，连接信息留空，显式启用，默认跳过。

- [support/ManualMigrationConsole.java](../src/test/java/com/datamigration/support/ManualMigrationConsole.java)：本机控制页，独立切断/恢复两端代理、显式执行与结束测试。
- [component/ManualMigrationConsoleTest.java](../src/test/java/com/datamigration/component/ManualMigrationConsoleTest.java)：控制访问、运行互斥、手动恢复、超时及取消清理。
- [e2e/ManualHostRecoveryIT.java](../src/test/java/com/datamigration/e2e/ManualHostRecoveryIT.java)：合成数据库验证源/目标实际TCP断连、同Runner恢复和重复执行。

- [component/MigrationLoggingTest.java](../src/test/java/com/datamigration/component/MigrationLoggingTest.java)：捕获 INFO/DEBUG/WARN/ERROR，验证配置与端点定位、并发批次归属、提交未知及二次审计失败保留原始原因、sequence 失败和秘密不泄漏。

- [component/AsyncMigrationTest.java](../src/test/java/com/datamigration/component/AsyncMigrationTest.java)：同步/异步交叉触发合并、已提交进度、失败恢复、重启、Spring单例、提交核实、sequence收尾、无键整表事务、中断及关闭清理。
- [support/MigrationAwait.java](../src/test/java/com/datamigration/support/MigrationAwait.java)：测试/命令行边界等待执行句柄并保留原异常类型，不改变正式接口的异步能力。
