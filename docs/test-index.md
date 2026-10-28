# 测试目录索引

统一源码根为 `src/test/java`。Surefire 发现 `*Test`，Failsafe 发现 `*IT`；JaCoCo 行/分支门槛仍各为 85%。目录按用途，Java package 仅在包内访问需要时例外保留。

| 文件 | Java package | 依赖与归类说明 |
| --- | --- | --- |
| [component/CredentialsDataSourceTest.java](../src/test/java/com/datamigration/component/CredentialsDataSourceTest.java) | `com.datamigration.autoconfigure` | 访问包内 CredentialsDataSource，保留 com.datamigration.autoconfigure。 |
| [component/DatabaseDialectTest.java](../src/test/java/com/datamigration/component/DatabaseDialectTest.java) | `com.datamigration.component` | 同时验证真实 H2 序列/事务锁及方言 SQL，归 component。 |
| [component/ExampleConfigurationTest.java](../src/test/java/com/datamigration/component/ExampleConfigurationTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/ExecutionIsolationTest.java](../src/test/java/com/datamigration/component/ExecutionIsolationTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/ExecutorScopeTest.java](../src/test/java/com/datamigration/component/ExecutorScopeTest.java) | `com.datamigration.component` | 依赖线程池的关闭、中断和清理，归 component。 |
| [component/JdbcMigrationEngineTest.java](../src/test/java/com/datamigration/component/JdbcMigrationEngineTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/JdbcRowsTest.java](../src/test/java/com/datamigration/component/JdbcRowsTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/MigrationAutoConfigurationTest.java](../src/test/java/com/datamigration/component/MigrationAutoConfigurationTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/MigrationStartupTest.java](../src/test/java/com/datamigration/component/MigrationStartupTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/ResilienceBoundaryTest.java](../src/test/java/com/datamigration/component/ResilienceBoundaryTest.java) | `com.datamigration.support` | 访问测试宿主的包内 JDBC 装饰器，保留 com.datamigration.support。 |
| [component/ResilienceConfigurationTest.java](../src/test/java/com/datamigration/component/ResilienceConfigurationTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/RowDigestTest.java](../src/test/java/com/datamigration/component/RowDigestTest.java) | `com.datamigration.component` | 依赖真实 H2 ResultSet 和类型归一化，归 component。 |
| [component/TableLockTest.java](../src/test/java/com/datamigration/component/TableLockTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/TcpFaultProxyTest.java](../src/test/java/com/datamigration/component/TcpFaultProxyTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [component/TransactionRecoveryTest.java](../src/test/java/com/datamigration/component/TransactionRecoveryTest.java) | `com.datamigration.component` | 组件依赖及生命周期验证。 |
| [demo/H2Demo.java](../src/test/java/com/datamigration/demo/H2Demo.java) | `com.datamigration.demo` | 手工运行入口，不参与 Maven 测试发现。 |
| [e2e/H2MigrationIT.java](../src/test/java/com/datamigration/e2e/H2MigrationIT.java) | `com.datamigration.e2e` | 完整迁移、数据类型或 TLS。 |
| [e2e/H2ScenariosIT.java](../src/test/java/com/datamigration/e2e/H2ScenariosIT.java) | `com.datamigration.e2e` | 完整迁移、数据类型或 TLS。 |
| [e2e/H2TlsIT.java](../src/test/java/com/datamigration/e2e/H2TlsIT.java) | `com.datamigration.e2e` | 完整迁移、数据类型或 TLS。 |
| [resilience/ConfiguredResilienceIT.java](../src/test/java/com/datamigration/resilience/ConfiguredResilienceIT.java) | `com.datamigration.resilience` | 显式指定配置与单一场景；默认跳过。 |
| [resilience/DevHarnessContractIT.java](../src/test/java/com/datamigration/resilience/DevHarnessContractIT.java) | `com.datamigration.resilience` | 用人工预备的 H2 验证 DEV 路径及凭据配置，默认可执行。 |
| [resilience/H2OwnershipIT.java](../src/test/java/com/datamigration/resilience/H2OwnershipIT.java) | `com.datamigration.resilience` | 故障、所有权或新宿主恢复。 |
| [resilience/H2ProcessRecoveryIT.java](../src/test/java/com/datamigration/resilience/H2ProcessRecoveryIT.java) | `com.datamigration.resilience` | 故障、所有权或新宿主恢复。 |
| [resilience/H2RecoveryIT.java](../src/test/java/com/datamigration/resilience/H2RecoveryIT.java) | `com.datamigration.resilience` | 故障、所有权或新宿主恢复。 |
| [resilience/H2ResilienceIT.java](../src/test/java/com/datamigration/resilience/H2ResilienceIT.java) | `com.datamigration.resilience` | 故障、所有权或新宿主恢复。 |
| [support/DatabaseFixture.java](../src/test/java/com/datamigration/support/DatabaseFixture.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/H2RecoveryWorker.java](../src/test/java/com/datamigration/support/H2RecoveryWorker.java) | `com.datamigration.support` | 原 H2ProcessRecoveryIT.Worker；子进程启动通过 class.getName() 引用。 |
| [support/MigrationAssertions.java](../src/test/java/com/datamigration/support/MigrationAssertions.java) | `com.datamigration.support` | 从 H2MigrationIT 抽出的共用数据/审计断言。 |
| [support/ResilienceContexts.java](../src/test/java/com/datamigration/support/ResilienceContexts.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/ResilienceHost.java](../src/test/java/com/datamigration/support/ResilienceHost.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/ResilienceSettings.java](../src/test/java/com/datamigration/support/ResilienceSettings.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/ResilienceSuite.java](../src/test/java/com/datamigration/support/ResilienceSuite.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/ResilienceWorker.java](../src/test/java/com/datamigration/support/ResilienceWorker.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/RowDigest.java](../src/test/java/com/datamigration/support/RowDigest.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/TcpDatabases.java](../src/test/java/com/datamigration/support/TcpDatabases.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/TcpFaultProxy.java](../src/test/java/com/datamigration/support/TcpFaultProxy.java) | `com.datamigration.support` | 数据库、宿主、代理、控制及校验设施。 |
| [support/UntrustedTlsClient.java](../src/test/java/com/datamigration/support/UntrustedTlsClient.java) | `com.datamigration.support` | 原 H2TlsIT.UntrustedClient；子进程启动通过 class.getName() 引用。 |
| [unit/JdbcValueConverterTest.java](../src/test/java/com/datamigration/unit/JdbcValueConverterTest.java) | `com.datamigration.unit` | 独立逻辑；不启动完整迁移。 |
| [unit/KeyCodecTest.java](../src/test/java/com/datamigration/unit/KeyCodecTest.java) | `com.datamigration.unit` | 独立逻辑；不启动完整迁移。 |
| [unit/SqlErrorsTest.java](../src/test/java/com/datamigration/unit/SqlErrorsTest.java) | `com.datamigration.unit` | 独立逻辑；不启动完整迁移。 |
| [unit/SqlSupportTest.java](../src/test/java/com/datamigration/unit/SqlSupportTest.java) | `com.datamigration.sql` | 访问 SqlSupport.tokens 包内方法，保留 com.datamigration.sql。 |
