# H2 本地演示与 DBeaver 连接

## 启动两个数据库

在 IDE 中导入根目录 pom.xml，选择 JDK 17，加载 Maven 的 test 依赖。运行 `src/test/java/com/datamigration/e2e/H2Demo.java` 的 main 方法，或直接调用 `startDemoDatabases()`。

该方法在 Java 进程内启动两个独立 H2 TCP 服务，数据库文件位于 `target/h2-demo-<时间戳>/`。源库创建 10,000 条 CUSTOMER 数据，以及含 3MB BLOB、空 BLOB、NULL、CLOB 的 DOCUMENTS 样例；目标库创建空业务表及 MIGRATION_AUDIT。数据均为程序生成的测试数据。

控制台显示实际 Source 和 Target JDBC URL，端口随机分配。保持进程运行，按 Enter 会停止两个服务。每次运行使用新目录；mvn clean 会清理 target，因此不要在演示运行期间执行 clean。

## 用 DBeaver 连接

在本机新建两个 H2 连接，选择按 URL 连接，分别填写控制台输出的完整 Source / Target 地址。建议驱动版本与项目的 H2 2.4.240 一致。

| 项目 | 值 |
| --- | --- |
| 驱动类 | org.h2.Driver |
| 用户名 | sa |
| 密码 | 空 |
| 源库 URL 样例 | jdbc:h2:tcp://localhost:6793/./source |
| 目标库 URL | 使用控制台 Target 输出，不复用源库端口 |

上面的 6793 只是示例端口。不要将 `jdbc:h2:` 重复拼接为 `jdbc:h2:h2:tcp:...`，也不要选择嵌入式文件地址另开数据库。

连接后可在两个库执行：

```sql
SELECT COUNT(*) FROM CUSTOMER;
SELECT ID, OCTET_LENGTH(CONTENT) FROM DOCUMENTS ORDER BY ID;
```

迁移前源库 CUSTOMER 为 10,000 行、目标库为 0 行。H2Demo 默认不启用 TLS；TLS 专门由 H2TlsIT 验证可信连接与不可信证书拒绝。

## 执行迁移

H2Demo 只准备数据库，不会自动执行迁移。宿主引入本组件及 test/demo 场景需要的 H2 驱动后，可参考 `src/test/resources/e2e.yml`：将两端连接填写为当前控制台 URL，用户名 sa、密码空、驱动 org.h2.Driver，并为两端池设置 maximum-pool-size=6。该 YAML 定义 CUSTOMER 的字段映射和 3 个表内 worker。

在宿主的 application.yml 中导入本地配置（本地文件不要提交）：

```yaml
spring:
  config:
    import: file:./migration-local.yml
```

手动调用注入的 `com.datamigration.api.MigrationService.migrate()`；或在配置完整时将 data-migration.run-on-startup 设为 true。配置 job-id 是恢复身份，同一数据集续传保持不变；默认追加模式下，对已有数据启动全新的 job-id 可能产生重复键错误。

不准备宿主应用也可以直接运行真实 Spring E2E，它自动启动并关闭自己的两端数据库，不使用手动启动的 H2Demo：

```sh
mvn -B -ntp verify -Dit.test=H2MigrationIT
mvn -B -ntp verify -Dit.test=H2TlsIT
```

完整本地验收使用 `mvn -B -ntp clean verify`。这些测试验证 H2 行为，不替代 Oracle/PostgreSQL 云端联调。
