# Oracle TCPS、PostgreSQL TLS 与 ECS 接入

这是部署说明，不是云端验收报告。本地 H2 的 TLS 测试已覆盖受信任证书成功、不受信任证书拒绝；真实 Oracle/PostgreSQL 驱动、IAM 与 ECS 仍须使用 DEV 环境验证。不要用 H2 compatibility mode 代替这些验收。

## Oracle 源连接

宿主引入 `com.oracle.database.jdbc:ojdbc11`，按数据库和驱动版本核对支持矩阵。源账号仅授予必要表的 SELECT 权限；不需要创建对象、更新表或读取源序列 NEXTVAL。

示例环境变量（主机、端口、服务名、DN 必须替换为 DBA 提供的值）：

```text
MIGRATION_SOURCE_URL=jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=oracle.example.internal)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=DEV))(SECURITY=(SSL_SERVER_DN_MATCH=YES)(SSL_SERVER_CERT_DN="CN=oracle.example.internal")))
```

将 CA 信任库以只读文件挂载给容器，通过 JVM 的 `javax.net.ssl.trustStore`、`javax.net.ssl.trustStoreType` 指向它。私钥/口令由运行环境的秘密管理机制提供，不提交到源码或镜像层；不要输出 JVM 秘密参数。双向 TLS 还需要客户端证书和私钥，具体 Wallet/PKCS12 配置由 DBA 和所选驱动确定。全局 JSSE 属性会影响宿主的其他 TLS 连接，应在独立迁移任务中配置并测试。

Oracle Thin 支持 TCPS、信任库及服务端身份匹配；参数细节以 [Oracle JDBC TLS 说明](https://blogs.oracle.com/developers/ssl-connection-to-oracle-db-using-jdbc-tlsv12-jks-or-oracle-wallets-122-and-lower) 和所用驱动文档为准。不要通过禁用 DN 匹配让错误证书“通过”。

## PostgreSQL 目标连接

宿主引入 `org.postgresql:postgresql`。本地/DEV 普通账号密码路径直接复用现有 YAML。目标 URL 示例：

```text
MIGRATION_TARGET_URL=jdbc:postgresql://postgres.example.internal:5432/dev?sslmode=verify-full&sslrootcert=/run/certs/postgres-ca.pem
```

`verify-full` 同时验证证书链和主机身份，CA 文件通过只读挂载提供；不能用 `sslmode=require` 代替完整身份验证。见 [pgJDBC TLS 文档](https://jdbc.postgresql.org/documentation/ssl/)。

迁移账号需要目标业务表 INSERT，显式重导才需要 DELETE；审计表需要 SELECT/INSERT/UPDATE 和行锁权限，序列校准需要读取和设置相关序列的权限。业务表、约束、审计表和序列由 DBA 预先创建。本组件不会建表或自动补权限。

## IAM：当前边界和接入方案

当前默认连接池只实现 YAML 静态账号密码，**没有 IAM token 自动生成/刷新功能**。不能将一次生成的 token 写入 YAML 后宣称支持长期运行的 IAM 连接池。

RDS IAM token 有效期为 15 分钟，创建新数据库连接时使用。见 [AWS RDS IAM 连接说明](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.Connecting.html)。由此，后续适配应在创建每个物理连接时获取有效 token，不能仅在应用启动时生成一次。已建立连接的生命周期与创建连接时的认证应分开测试。

接入方式：宿主注册自定义 `MigrationEngine` Bean，内部仍使用 `JdbcMigrationEngine`，传入自定义 `ConnectionAccess`。该实现负责两个独立连接池、READ_COMMITTED/autoCommit=false、关闭生命周期；目标池底层连接工厂从 AWS SDK 默认凭据链获得 ECS task role 凭据，按 endpoint/region/username 生成 token，再建立开启 verify-full 的连接。`JdbcMigrationEngine` 的连接预算和 endpointIdentity 必须与配置一致。此扩展尚需实现及云端测试，不要求改动业务 INSERT/恢复算法。

ECS **task role** 用于应用的 AWS API 权限，**task execution role** 用于拉取镜像、日志及任务配置中的秘密注入；不要混用。依据 [AWS ECS IAM 角色说明](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/security-iam-roles.html) 配置最小权限，不在镜像中保存长期 access key。

## 作为 ECS 一次性任务运行

该项目仍是普通依赖 JAR，不新增 runner Maven 子模块。由宿主提供一个非 Web Boot 启动类，参考 `examples/ecs/MigrationJobApplication.java`；将宿主打包为可执行 JAR。开启 run-on-startup，成功后关闭 Spring Context 正常退出；异常传播为非零进程退出码。宿主若已有常驻服务，不应将服务成功启动等同于一次性 ECS 任务已完成。

`examples/ecs/Dockerfile` 使用宿主产物 host.jar，构建前须完成宿主自己的构建。镜像和例子未在 ECS 部署验证。

任务定义要点：

- 使用独立一次性任务，配置所需 CPU/内存、日志、task role、execution role 和证书只读挂载。
- `SPRING_MAIN_WEB_APPLICATION_TYPE=none`、`DATA_MIGRATION_RUN_ON_STARTUP=true`；配置文件通过 `SPRING_CONFIG_IMPORT=file:/run/config/migration.yml` 导入。
- 两端 URL、用户名和密码通过环境与 secret 注入，保持 `MIGRATION_*` 变量名与示例 YAML 一致。IAM 路径须先实现上述连接工厂。
- 固定 `DATA_MIGRATION_JOB_ID`，ECS 重试同一工作时保持不变；仅在明确全量重导时换新 job-id 并启用 CLEAR_AND_REIMPORT。
- 网络须能解析并访问 Oracle DEV TCPS 和目标 PostgreSQL 端口；容器内不能使用本地 H2 示例的 localhost 地址。
- 审计保存在目标库，容器重建不丢检查点；证书和密码不写入任务日志。
- 为中断/回滚预留停止宽限时间，并演练强制终止后的同 job-id 恢复。无键大事务的回滚耗时需按真实数据量评估。

## DEV 验收记录应包含

1. 两端真实驱动版本、数据库版本、TLS 协议/证书信息；不记录口令或 token。
2. 正确证书成功；错误 CA、过期证书、错误服务端身份被拒绝。
3. Oracle NUMBER、日期/时区、NULL、数 MB BLOB 到 PostgreSQL 的逐字段比对。
4. 源/目标断连、提交响应丢失、ECS 任务终止后恢复；最终行数、内容、检查点无重复或缺口。
5. IAM 创建连接、token 过期后新连接、池扩容/回收重连、IAM 权限撤销失败路径。
6. 有键分批恢复、无键全表回滚、新 job-id 重导、序列不回退及失败后单独收尾。
