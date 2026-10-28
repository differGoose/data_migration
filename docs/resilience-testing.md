# 可切换环境的故障恢复测试

默认 `mvn -B -ntp clean verify` 只运行本地测试。`ConfiguredResilienceIT` 没有 `resilience.config` 时跳过，Oracle/PostgreSQL 驱动只由 `resilience-dev` profile 引入。真实 DEV 验收需操作者提供连接配置、稳定源数据范围和已建好的专用目标表/审计表。

## 统一入口与配置覆盖

在仓库根目录执行；PowerShell 下保留参数引号：

```sh
mvn -B -ntp verify "-Dit.test=ConfiguredResilienceIT" "-Dresilience.config=file:examples/resilience/scenarios.yml,file:examples/resilience/h2.yml" "-Dresilience.scenario=SOURCE_OUTAGE"
```

`resilience.config` 是 Spring 配置位置的逗号分隔列表，后加载文件覆盖前面的同名配置；入口的 `resilience.scenario` 覆盖 YAML 中的场景。Spring 的列表是**整体替换**，不是按下标或任务名合并：覆盖 `data-migration.tables`、`fields` 或 `resilience.configuration-classes` 时必须写出完整列表。`ResilienceConfigurationTest` 验证这些语义。

公共配置在 `examples/resilience/scenarios.yml`，H2 与 DEV 示例分别在 `h2.yml` 和 `oracle-postgresql.yml`。`data-migration` 仍负责连接、SQL、表映射和策略；`resilience` 独立描述环境、场景、超时、代理、触发点和校验查询。

可选场景：

| 场景 | 故障动作 |
| --- | --- |
| `SOURCE_OUTAGE` | 指定业务提交后，关闭源端已有代理连接并拒绝新连接 |
| `TARGET_OUTAGE` | 指定业务提交后，关闭目标端已有代理连接并拒绝新连接 |
| `APP_BEFORE_COMMIT` | 指定业务提交前，强制结束宿主 JVM |
| `APP_AFTER_COMMIT` | 指定业务提交成功后，强制结束宿主 JVM |

`trigger-task` 是启用任务的 `name`；`trigger-commit` 从 1 开始，按该任务的业务提交计数，不含审计提交。触发任务必须 `worker-count: 1`，避免并发提交顺序不确定；数据量必须大于触发提交批次乘以 `commit-size`。公共示例在第 2 次提交触发，四个 H2 场景均验证已有部分结果的恢复。

父进程持有两个 TCP 代理，宿主在独立 JVM 执行。JDBC 装饰器按配置生成的完整 `insert-sql` 匹配已执行的业务写入，利用本地控制目录中的 `ready` / `release` 文件握手，父进程确认故障注入后才允许继续。无需固定时间等待或固定业务表名。每个阶段受 `phase-timeout` 约束；超时会终止子进程。数据库故障必须导致本轮迁移失败，再恢复代理并启动新的宿主 JVM；没有为迁移引擎增加无限重试或自动恢复。

## DEV 准备与执行

1. 将 `examples/resilience/oracle-postgresql.yml` 复制为根目录下被 Git 忽略的 `resilience-local-dev.yml`，替换业务 SQL、表名、字段映射和校验 SQL。源查询必须是测试期间不变的范围，校验查询字段顺序与映射一致，并按唯一键稳定排序。
2. 人工创建专用目标表和审计表，审计结构参照项目的审计建表脚本。测试框架不会在 DEV 建表、造数、清表、删除数据或修改源数据。每个新场景的专用目标表必须为空，且该 `job-id` 不应已有任务汇总；状态不符立即停止，保留现场。
3. 设置示例引用的 `DEV_ORACLE_*`、`DEV_PG_*`、`DEV_ID_MIN`、`DEV_ID_MAX` 和 `RESILIENCE_JOB_ID` 环境变量。不同独立场景必须使用不同 `RESILIENCE_JOB_ID`，不要在 ID 中放秘密。
4. 显式选择一次一个场景运行：

```sh
mvn -B -ntp -Presilience-dev verify "-Dit.test=ConfiguredResilienceIT" "-Dresilience.config=file:examples/resilience/scenarios.yml,file:resilience-local-dev.yml" "-Dresilience.scenario=TARGET_OUTAGE"
```

每次调用自动执行故障、恢复、再次执行三个阶段，共用同一个 job-id、代理端口和迁移配置。故障阶段之后允许本轮部分结果，续跑与再次执行不要求目标为空。重新调用入口被视为**新的一轮**，不会接管已有非空目标；父进程自身中断后应保留现场，由操作者评估并使用原配置恢复，不能直接当作新场景重复执行。

DEV 验收前应配置 JDBC 驱动连接/读取超时，避免父进程预检的连接建立无限等待；`phase-timeout` 约束子进程及校验查询，不替代驱动的网络超时。源账号应只有读取权限，目标账号限定在人工准备的测试对象上。

## 动态凭据与 TLS

支持 `data-migration.source.credentials-provider` / `target.credentials-provider`；将宿主的解密或 IAM 配置类全名放入 `resilience.configuration-classes`，类必须位于 test classpath。框架在父进程及全部子进程都注册这些类。提供器配置见 [数据库凭据扩展](database-credentials.md)。启用提供器时可不设置明文密码环境变量。

TCP 代理原样转发字节，TLS 仍端到端验证。PostgreSQL `verify-full` 或 Oracle TCPS 使用的证书主机名必须解析到本地代理，代理 upstream 则必须独立解析到真实服务器；不要把 upstream 也解析回本机形成循环。IAM token 应按真实服务端主机和端口签发，不能使用临时代理端口签发。Oracle RAC 重定向、多地址故障转移等可能绕开代理的配置不适合该测试；预检要求两端都实际经过代理。

## 验收与报告

运行目录在 `target/resilience-reports/`。`manifest.properties` 用白名单保存运行 ID、job-id、场景、触发点、代理端口及提交大小，不输出 URL、密码、SQL 或业务行。`result.properties` 标记最终结果；`fault.log`、`resume.log`、`repeat.log` 和 `runtime.properties` 属于本地诊断文件，不应提交或公开分享。

验收包括：故障已触发且本轮未完成、宿主崩溃的提交前后行数边界、源数据摘要未改变、恢复后目标行数及字段摘要与源一致、汇总和成功批次审计合计一致，以及再次执行保持数据/审计合计不变。数据摘要按映射类型归一化并流式计算，不保存业务数据。

`DevHarnessContractIT` 使用人工预备的本地 H2 模拟 DEV 路径，验证凭据 Bean、非空目标拒绝及完整续跑；它不代表真实 Oracle/PostgreSQL、IAM 或 TCPS 已通过验收。
