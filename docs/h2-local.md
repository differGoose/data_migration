# H2 本地演示

本仓库只使用合成数据，不访问真实数据库。IDE 导入 Maven，选择 JDK17/21，使用 test classpath。

## 方式一：直接看“迁移完成后才启动宿主”

运行 [BeforeStartupDemo.main](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java)。它在 target 下创建独立 H2 数据库并造 10000 行 CUSTOMER，宿主创建两个 Hikari DataSource，传给 MigrationRunner。migrate 调用两次验证同 job 完成后不重插；迁移成功且资源归还后才运行非 Web 示例宿主，随后关闭进程。

此示例是测试源码中的可运行入口，不打进生产 JAR。第一次看本模块建议从这里开始，再阅读 [真实宿主接入步骤](getting-started.md)。

## 方式二：启动两个数据库，用 DBeaver 查看

运行 [H2Demo.main](../src/test/java/com/datamigration/demo/H2Demo.java)，它只准备源/目标库，不执行迁移。源含10000行 CUSTOMER及 BLOB/CLOB DOCUMENTS，目标有空业务表和审计表。控制台输出随机端口及完整 JDBC URL，账号 sa、密码空。保持进程运行，按 Enter 停止。

DBeaver 建两个 H2 URL 连接，使用控制台原样输出的 Source/Target URL，驱动 org.h2.Driver，版本与 Maven H2 对齐。不要只替换端口而保留错误数据库路径，也不要选嵌入式地址另开库。Docker 的 localhost 是容器自身。

```sql
SELECT COUNT(*) FROM CUSTOMER;
SELECT ID, OCTET_LENGTH(CONTENT) FROM DOCUMENTS ORDER BY ID;
```

独立宿主连接这些数据库时，由宿主创建两个 H2 DataSource（需自加 H2 驱动依赖），直接传给 Runner；迁移配置复制 [H2规则](../examples/resilience/h2.yml) 中的 data-migration 节并加稳定 job-id，不复制测试专用 resilience 节。源/目标连接 URL 在宿主工厂中设置。也可将 [接入模板](../examples/migration.yml) 的源 BUSINESS.CUSTOMER 改成 CUSTOMER、目标 customer_copy 改成 CUSTOMER。

迁移后目标 CUSTOMER 应为10000行，按ID比对NAME/AMOUNT，审计汇总 committed_rows=10000、skipped_rows=0。同配置同job重跑仍为10000行；H2Demo重启会创建新的目录，不会继承上次的数据库或检查点。演示运行时不要 mvn clean，它会删除 target。

## 自动测试

```sh
mvn -B -ntp verify "-Dit.test=H2MigrationIT"
mvn -B -ntp verify "-Dit.test=H2TlsIT"
```

测试自行管理数据库及宿主 DataSource，不需要先开 H2Demo。TLS测试临时生成证书；H2结果不代替真实Oracle/PostgreSQL联调。
