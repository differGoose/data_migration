# 手动切断真实数据库连接，再调用迁移续跑

入口是 [HostMigrationE2EIT](../src/test/java/com/datamigration/e2e/HostMigrationE2EIT.java)。普通模式仍是宿主创建DataSource，调用MigrationRunner一次；手动模式增加两个测试TCP代理和本机控制页。两种模式均只读取正式的data-migration配置，不需要e2e配置段。

## 填写连接与代理地址

连接信息仍在测试类顶部，本地填写，不提交真实信息。手动模式需填写：

| 常量 | 填写内容 |
| --- | --- |
| SOURCE_UPSTREAM_HOST / PORT | 真实Oracle地址、端口，默认端口1521 |
| TARGET_UPSTREAM_HOST / PORT | 真实PostgreSQL地址、端口，默认端口5432 |
| SOURCE_JDBC_URL | 指向本地代理的Oracle URL模板，服务名仍为真实服务名 |
| TARGET_JDBC_URL | 指向本地代理的PostgreSQL URL模板，库名仍为真实库名 |
| SOURCE_USERNAME / PASSWORD、TARGET_USERNAME / PASSWORD | 对应真实数据库账号与密码 |

URL格式示意（不包含账号密码）：

```java
SOURCE_JDBC_URL = "jdbc:oracle:thin:@//127.0.0.1:{proxy-port}/YOUR_SERVICE";
TARGET_JDBC_URL = "jdbc:postgresql://127.0.0.1:{proxy-port}/YOUR_DATABASE";
```

`{proxy-port}` 保留原样，测试分配两个本地端口后分别替换。实际路径为：宿主连接池 → 本机代理 → 真实数据库。不能在URL中继续直连真实地址，否则切断代理不会影响迁移。退出手动模式时，将URL改回正式直连URL。

代理透明转发TCP字节，TLS仍由数据库与驱动完成。启用TLS时保留原有信任与服务器身份校验；需要使用证书主机名的URL时，由本机解析将该名称指向代理，并确保upstream独立连接真实数据库，不能循环回代理。不要为此关闭证书验证。单端点以外的Oracle重定向、多主机或故障转移地址也必须经过受控路由，否则代理无法控制旁路连接。连接和网络读取超时由宿主按实际驱动配置。

## 配置文件与启动

`src/test/resources/application.yml` 仍填写正式的data-migration规则，包括稳定job-id、表映射、batch-size和策略。目标业务表、审计表提前准备。测试不会额外建表、清表或造数；显式CLEAR_AND_REIMPORT仍会由正式引擎按配置删除目标表数据。

IDE的JUnit运行配置中添加两个VM参数：

```text
-Dhost.e2e.enabled=true -Dhost.e2e.manual=true
```

或使用Maven：

```powershell
mvn -B -ntp test-compile failsafe:integration-test failsafe:verify "-Dit.test=HostMigrationE2EIT" "-Dhost.e2e.enabled=true" "-Dhost.e2e.manual=true"
```

启动后控制台打印 `Manual migration control: http://127.0.0.1:端口/随机路径/`。在浏览器中打开完整地址。页面仅监听本机，随机路径用于限制控制访问，不要公开分享这个地址。

## 手动断开和续跑

1. 点击“执行 / 续跑”。状态变为RUNNING后，可以点击“断开 source”或“断开 target”。代理关闭已有连接，并拒绝新连接。
2. 保持断开，等待本轮迁移状态变为FAILED。引擎原有的有限瞬时重试仍生效；太早恢复连接可能使本轮重试成功，这是正常行为。
3. 点击对应的“恢复 source”或“恢复 target”。这只是重新允许建连，不会自动重启迁移，也不会复活已关闭的JDBC连接。
4. 再点击“执行 / 续跑”。模拟宿主先淘汰池中旧连接，让池重新建立物理连接；DataSource和Runner对象不变。使用原配置、原job-id，按审计恢复未完成块；成功块跳过。执行中重复点击会立即拒绝。这个显式重连动作属于测试宿主，不是引擎增加了自动恢复。
5. 最后一次状态为SUCCEEDED时，点击“成功后结束测试”，JUnit正常结束。还可再次点击“执行 / 续跑”观察已完成任务的行为。

“终止测试”会使测试失败，并请求取消正在运行的迁移，等待线程和事务清理后关闭资源。控制会话默认最长30分钟，超时同样失败；可用 `-Dhost.e2e.manual-timeout-seconds=3600` 调整为1小时。驱动不响应中断时，清理仍需等待其网络超时。

控制页保留失败状态供手动恢复，不会因为某次预期断网失败立刻退出，也不会无限自动重跑。结束测试前必须有一次成功完成；页面不输出业务行、连接配置或原始驱动异常。详细迁移结果可结合目标审计表查看。

同job恢复期间不修改配置、实际数据库或源数据范围。已经成功的job再次运行可能立即返回，来不及注入故障；需要新一轮搬运时，应按正式迁移规则规划新job及目标数据处理，不要删除检查点制造故障。

## 验证边界

手动模式的真实Oracle/PostgreSQL连接由使用者提供。本地 [ManualHostRecoveryIT](../src/test/java/com/datamigration/e2e/ManualHostRecoveryIT.java) 只使用自己准备的合成H2验证实际TCP断连、同Runner恢复及重复调用；它不会被真实入口用作默认或回退环境。

## 查看迁移停在哪一步

正式 Runner 默认输出 INFO 关键节点，源/目标连接、表配置预检、数据提交与 sequence 收尾分别有独立阶段。按 runId、task、batch 查找对应日志；逐批次定位可在宿主的 logback-test.xml 中将 com.datamigration 设为 DEBUG。典型失败及配置方法见 [日志与排错](logging-and-troubleshooting.md)。测试在正式宿主启动前调用时，不应依赖 Boot 尚未应用的 logging.level。
