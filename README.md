# data_migration

Java 17 / Spring Boot 4.1.1 / JDBC / HikariCP 数据迁移组件，优先面向静态 Oracle 数据源 → PostgreSQL。

最低运行版本为 Java 17，使用 `--release 17` 编译；已在 JDK 17.0.16 上通过完整本地验收。此前按 Java 21 编译的旧 JAR 需要重新构建安装，不能直接用于 JDK 17 宿主。CI 配置同时验证 Java 17 和 21。

**单模块、普通 JAR、Java 方法入口，不提供 REST API。** 已实现 JDBC 迁移引擎、持久化续传和双 H2 测试；完整验收进度见 [验收记录](docs/acceptance-progress.md)。真实 Oracle/PostgreSQL、IAM 和 ECS 尚未联调。

## 从哪里开始读

第一次使用，建议按下面顺序阅读；无需先理解检查点和锁的内部实现。

| 你想做什么 | 对应文档 |
| --- | --- |
| 把组件放进自己的 Spring Boot，实际迁移一张表 | [宿主接入完整步骤](docs/getting-started.md) |
| 理解 SQL 映射、三个批次参数、连接池及 job-id | [配置解释与例子](docs/configuration.md) |
| 在本机准备 H2，查看源库与目标库 | [H2 本地演示](docs/h2-local.md) |
| 接入项目自己的密码解密或 IAM | [数据库凭据扩展](docs/database-credentials.md) |
| 判断部分成功、断网续跑、提交未知和重新全量迁移 | [运维与常见问题](docs/operations.md) |
| 连接 Oracle TCPS、PostgreSQL TLS，或放进 ECS | [云端部署步骤](docs/cloud-deployment.md) |
| 人为断网/杀宿主，验证恢复是否正确 | [恢复测试指南](docs/resilience-testing.md) |
| 理解底层事务、范围规划和锁协议 | [架构说明](docs/architecture.md) |
| 查测试位置及哪些内容已经验收 | [测试索引](docs/test-index.md)、[验收记录](docs/acceptance-progress.md) |

整体流程是：**宿主引入 JAR → 人工准备目标表和审计表 → 填 YAML → 注入并调用 migrate() → 检查各表结果 → 必要时用原 job-id 续跑**。

## 使用方式

宿主引入依赖后，自动获得 `MigrationService` Bean，无需额外组件扫描或启用注解。

当前版本尚未发布到 Maven Central。先克隆本仓库并执行 `mvn install` 安装到本机 Maven 仓库，再由宿主引入下述依赖；团队使用时可部署到自己的 Maven 私服。

```sh
git clone https://github.com/differGoose/data_migration.git
cd data_migration
mvn clean install
```

```xml
<dependency>
  <groupId>com.datamigration</groupId>
  <artifactId>data_migration</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

宿主使用 Spring Boot 4.1.1 的依赖管理，并按实际数据库添加 `com.oracle.database.jdbc:ojdbc11`、
`org.postgresql:postgresql` 等 runtime 驱动依赖。框架不捆绑业务数据库驱动，不承诺 Boot 2/3 兼容。

只有一个自动执行开关：

```yaml
data-migration:
  run-on-startup: false
```

- `false`（默认）：不自动迁移，`MigrationService` 仍可注入、调用。
- `true`：Spring Boot 启动时同步调用同一个 `migrate()` 方法；执行失败使启动失败。
- 无论开关状态如何，手动调用都不受限制。没有单独的全局 `enabled` 开关。

通过构造器注入后调用：

```java
@Service
public class BusinessService {
    private final MigrationService migrationService;

    public BusinessService(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    public MigrationResult migrateData() {
        return migrationService.migrate();
    }
}
```

对应类型为 `com.datamigration.api.MigrationService` 和 `MigrationResult`。
方法同步执行，返回结果或抛出异常；未提供 HTTP 端点、定时任务或管理界面。

## 配置和校验时机

完整 Oracle → PostgreSQL 示例：[examples/migration.yml](examples/migration.yml)。
将其中的 `data-migration` 节合并到宿主的 application.yml，或通过
`spring.config.import: optional:file:./migration.yml` 导入外部文件。
示例文件不打包进 JAR，项目不附带会影响宿主的 application.yml。

首次调用默认 `migrate()` 时绑定并校验配置，成功后缓存，不支持热更新。
不自动运行且未调用时，允许没有数据库配置或配置尚未完成，不创建迁移连接池。
显式请求连接池 Bean 也会触发配置校验。缺失配置、非法值和未知字段会明确失败。
若开启启动执行，校验自然发生在启动期间。

两端密码使用环境变量：`MIGRATION_SOURCE_PASSWORD`、`MIGRATION_TARGET_PASSWORD`；
URL、用户名对应变量见示例。不要把密码放进 JDBC URL，不要记录源数据或参数值。
配置对象的 toString 已脱敏；避免启用输出数据库配置的 DEBUG/TRACE 日志。

| 配置 | 默认值与含义 |
| --- | --- |
| run-on-startup | false，只控制启动自动执行 |
| job-id | 必填；同一次迁移的稳定标识，恢复时保持不变 |
| source / target | JDBC URL、账号、密码、驱动、pool |
| pool.maximum-pool-size / minimum-idle | 4 / 0；示例目标池为 5 |
| pool.connection-timeout | 30s，至少 250ms |
| pool.idle-timeout / max-lifetime | 10m / 30m；0 表示禁用，否则至少 10s / 30s |
| strategy.fetch-size | 1000，JDBC 读取提示 |
| strategy.batch-size | 500，每次 executeBatch 行数 |
| strategy.commit-size | 2000，每个目标事务处理的源记录数 |
| strategy.skip-limit | 0，每张表、每个 job-id 累计坏数据容忍数；续传不清零 |
| strategy.transient-retry-count | 3，不含初次执行 |
| strategy.retry-initial-backoff / retry-max-backoff | 1s / 10s，正数且最大值不小于初始值 |
| strategy.max-concurrent-tables | 4，表任务并发上限 |
| strategy.audit.enabled / table-name | true / migration_audit；本版持久化恢复要求审计开启 |
| strategy.max-batch-bytes / max-lob-size | 16MB / 32MB；限制批处理估算内存和单字段大小 |
| tables[].enabled | true，控制该表是否参与迁移 |
| tables[].target-table | 必填，预建目标表，只支持普通 table 或 schema.table 标识符 |
| tables[].primary-key | 查询结果中的单列或有序联合键；空列表表示整表事务 |
| tables[].worker-count | 1；有键表可增加，无键表必须为 1 |
| tables[].mode | APPEND_RESUME；CLEAR_AND_REIMPORT 显式允许清除目标表 |
| tables[].sequence | 默认 enabled=false；开启后填写 name 和 target-column |
| tables[].select-sql / insert-sql | 人工提供 SQL，INSERT 使用 ? 占位符 |
| tables[].fields | 按列表顺序绑定 JDBC 参数，从 1 开始 |
| fields[].source-column | SELECT 结果列标签，建议显式唯一别名 |
| fields[].target-jdbc-type | JDBCType 枚举，如 BIGINT、VARCHAR、TIMESTAMP |

所有声明的表任务均校验，至少一个表启用。任务名去除首尾空白后忽略大小写判重。
引擎执行前解析 SQL，并核对结果列、参数数量、键的非空性和唯一性。INSERT 只允许单行 VALUES 模板，字段顺序就是参数顺序。SELECT 可包含 ORDER BY，引擎在外层包装范围条件和稳定排序；不支持 CTE、动态参数和锁定查询。SQL 来自可信管理员，语法校验不能替代数据库只读权限。

## 事务、恢复与重导

源数据必须在整个迁移及恢复期间保持不变。行数检查能发现部分变更，不能检测所有更新；本版不提供跨库一致快照或 CDC。

- 有键表按 commit-size 划分固定键范围，每批的数据与成功检查点在同一个目标事务中提交。并行批次分别保存边界，不使用一个全局最大主键冒充进度。
- 同一 job-id 再次调用会跳过成功批次，继续失败或未完成批次。SQL、映射、键、端点身份及关键策略变更会导致指纹不匹配；不要手工修改检查点。
- 无键表全部 INSERT 在一个目标事务中完成；失败回滚，重试从头读取。无键表不允许跳过坏数据。executeBatch 仍可分批，但不会分批 commit。
- A 表失败不会取消 B 表或后续表；所有任务结束后抛出 MigrationException，可通过 result() 查看各表结果和已提交进度。
- 瞬时故障仅在确认回滚后重试。COMMIT 响应丢失时使用新连接锁定检查点核实；无法核实时报告 COMMIT_UNKNOWN，不盲目重放。
- CLEAR_AND_REIMPORT 使用 DELETE。无键表的清除、导入、成功检查点为一个事务；有键表先持久化清除和 prepared 标记，再分批迁移。恢复相同 job-id 不再次清除。想完整重导时显式选择该模式并使用新的 job-id。
- 目标外键、触发器和权限由使用方管理，不自动级联删除。并行任务应相互独立。

序列校准是数据提交后的独立收尾步骤：将目标命名序列推进到目标 ID 最大值之后，不回退已有水位。只支持步长 1、非循环序列；不是复制 Oracle 缓存的 NEXTVAL。收尾失败返回 FINALIZATION_FAILED，修复后使用同一 job-id 重试，不重插数据。迁移期间目标业务写入和序列分配必须停止。

每张有键表占用 `worker-count + 1` 个源连接、`worker-count + 2` 个目标连接；无键表需要 1 个源连接、3 个目标连接。连接池容量须覆盖最大同时运行的表组合，启动执行前会检查预算。目标的额外连接用于协调与持有表锁。

BLOB/CLOB 在源游标有效期内流式写入并释放资源；CLOB 大小按 UTF-16 单元估算。LOB 不跨行缓存。普通批次受行数和估算字节数约束，单条记录仍可能大于 max-batch-bytes；max-lob-size 是单字段限制，不是 JVM 总内存硬上限。

## 目录与扩展

```text
data_migration/
├── pom.xml                          # 唯一 Maven 模块，packaging=jar
├── src/main/java/com/datamigration/
│   ├── api/                         # MigrationService、结果
│   ├── model/                       # 不可变任务模型
│   ├── properties/                  # 配置、按需绑定校验
│   ├── autoconfigure/               # 自动装配、连接池、启动触发
│   ├── execution/                   # 调度、事务恢复、错误分类
│   ├── jdbc/                        # 有界读取、参数绑定、LOB 写入
│   ├── sql/                         # SQL 预检、键范围、检查点键编码
│   ├── dialect/                     # H2 / PostgreSQL 序列校准
│   ├── conversion/                  # 类型转换接口
│   └── audit/                       # 检查点、表锁、执行者隔离
├── src/main/resources/META-INF/spring/
├── src/test/java/
├── examples/migration.yml
└── docs/
```

不再有 core/starter/runner 子模块，也没有独立 main 类；由宿主 Spring Boot 启动。
使用 AutoConfiguration.imports 自动发现服务。迁移连接池通过专用容器持有，
不注册 DataSource Bean，不覆盖宿主数据源或事务管理器。
连接池按需创建，容器随 Spring Context 关闭。引擎使用 try-with-resources 归还连接。

实现 `MigrationEngine` 并注册 Bean，默认服务就会将 `MigrationPlan` 交给它执行。
也可提供自己的 `MigrationService` Bean 替换默认入口，此时配置校验由该实现负责。
默认提供 JdbcMigrationEngine。可注册 ValueConverter Bean 扩展普通值转换；LOB 仍由 JDBC 写入器管理。早期保留的 TableReader、BatchWriter、TableTaskExecutor、AuditRepository 接口尚不是默认引擎的 Bean 替换点。

## 构建与验收

需要 JDK 17 和 Maven 3.9+。在项目根目录执行：

```powershell
# JAVA_HOME 指向本机 JDK 17 安装目录；先用 java -version 和 mvn -version 确认。
mvn -B -ntp clean verify
mvn install
```

产物：`target/data_migration-0.1.0-SNAPSHOT.jar`，可直接作为宿主依赖，不是可执行 Boot JAR。
运行依赖由 Boot BOM 管理；JUnit 5.13.4 BOM 显式覆盖 Boot 默认的 JUnit 6，满足原测试栈要求。

Surefire 执行单元测试；Failsafe 执行 `*IT`，包括双 H2 TCP、10,000 行、TLS 正反例、断连和进程强制退出恢复。TLS 测试使用 JDK 自带 keytool 生成临时证书。JaCoCo 单独统计 Surefire 覆盖率，LINE 和 BRANCH 均须 >=85%，不使用 E2E 补足 UT 覆盖率；报告在 `target/site/jacoco-ut/index.html`。

在 IDE 中直接运行 `src/test/java/com/datamigration/demo/H2Demo.java` 的 `main` 或 `startDemoDatabases()`，即可启动两个独立 H2 TCP 服务并创建 10,000 条源数据。控制台打印随机端口和 JDBC 地址，测试账号为 sa/空密码，按 Enter 停止。文件保留在 target/h2-demo-*，只用于本地测试。参考 src/test/resources/e2e.yml 填写地址，IDE 必须使用 test classpath。此演示方法不会阻塞 mvn verify。

目标业务表和审计表必须提前创建；引擎不自动执行 DDL。正式 [PostgreSQL 审计模板](src/main/resources/db/postgresql/migration-audit.sql) 和 [H2 模板](src/main/resources/db/h2/migration-audit.sql) 随 JAR 提供。审计表也承载执行锁记录，不可在迁移运行期间清空。

实现细节见 [设计文档](docs/architecture.md)。框架锁只能协调使用同一个目标库、同一个审计表的本组件实例；不能阻止外部程序改表。建议只在一个宿主实例开启启动迁移。

云端接入见 [Oracle TCPS / PostgreSQL TLS / IAM / ECS 说明](docs/cloud-deployment.md)。IAM token 由宿主提供器在新建物理连接时生成；真实 IAM 仍需联调。ECS 示例交由宿主打包运行，不是本组件的独立 runner 模块。

## 本地演示与贡献

H2Demo 的启动、DBeaver 连接和迁移方法调用见 [H2 本地指南](docs/h2-local.md)。它只准备样例数据库，迁移需由宿主调用 `MigrationService.migrate()`，或执行已有 E2E 用例。

提交范围、构建和报告缺陷的方法见 [CONTRIBUTING.md](CONTRIBUTING.md)，版本变化见 [CHANGELOG.md](CHANGELOG.md)。仓库只保存源码、合成测试数据的生成代码、配置模板和文档。真实数据库、日志、IDE 配置、密码和证书文件不提交。

## 数据库凭据扩展

源库和目标库支持通过 `credentials-provider` 指定宿主 Spring Bean，自定义密码解密、密钥服务读取或 AWS IAM token 生成。旧静态密码配置保持兼容，参见 [接入示例与生命周期说明](docs/database-credentials.md)。

### 故障恢复测试与目录

测试按 `unit`、`component`、`e2e`、`resilience`、`support`、`demo` 组织在 `src/test/java/com/datamigration` 下。见[逐类测试索引](docs/test-index.md)及[H2 / DEV 故障恢复指南](docs/resilience-testing.md)。默认 CI 只运行本地测试；真实 Oracle → PostgreSQL 需显式配置、选择单一场景并启用 `resilience-dev` profile。
