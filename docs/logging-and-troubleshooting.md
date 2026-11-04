# 迁移日志与排错

组件通过 SLF4J 的 `com.datamigration` logger 输出英文日志。先按 `runId` 找到同一次调用，再按 `task`、`batch`、`stage` 和 `endpoint` 定位失败位置。连接失败、配置错误和 sequence 收尾失败分别有自己的阶段，不必依靠原始驱动异常消息判断。

## 日志级别与关联字段

| 级别 | 内容 |
| --- | --- |
| INFO | 配置加载/绑定、数据源选择、容量预检、首次连接成功、表预检、恢复/跳过、数据完成、sequence 收尾、最终状态与耗时 |
| DEBUG | 借还连接、范围规划、逐批次开始和提交；默认 INFO 不输出这些细节 |
| WARN | 有限重试的次数、等待时间及安全原因，提交结果核实，以及失败后的审计写入再次失败 |
| ERROR | 原始失败所在任务、阶段、属性路径及安全错误分类 |

`runId` 是本次调用的内部执行编号，默认引擎返回的 `MigrationResult.runId()` 与执行日志一致。它不代替 `job-id`，不会改变恢复身份；构建 Runner 和之后调用 migrate 是不同的诊断操作。`job` 是配置中的 job-id，`task` 是表任务名，`batch` 为正数时表示具体事务块，`-1` 表示当前没有批次上下文。并发任务的日志会交错，用这些字段关联，不要只看相邻行。

`property` 是应检查的配置位置，例如 `data-migration.tables[0].fields[1].source-column`，不是该属性的值。配置尚未绑定、没有表上下文或端点不适用时，相应字段显示 `-`。名称中的控制字符会转义为下划线。

## 从阶段判断应该检查哪里

| stage | 检查内容 |
| --- | --- |
| MIGRATION_REUSE | 同实例重复调用，返回运行中的原句柄；不会再次执行 |
| CONFIG_LOAD / CONFIG_BIND | 配置资源是否可读、工作目录、classpath、YAML 缩进、未知字段和校验约束。未知字段日志只列属性路径；绑定错误不打印被拒绝的值 |
| DATASOURCE_RESOLVE | 直接入口是否提供两端 DataSource；Spring 入口每端是否恰好有一个标记 Bean |
| CAPACITY_CHECK | 自动读取的 HikariCP maximumPoolSize 是否达到 YAML 并发预算；显式模式同时检查声明的迁移额度。有键表每端 W+1，无键表源1/目标2；多表同时运行需合计预算，详见配置文档 |
| SOURCE_CONNECT / TARGET_CONNECT | 引擎开始打开对应端连接；正式 Runner 随后细分为获取连接和初始化事务属性 |
| CONNECTION_ACQUIRE | 宿主 DataSource 借连接失败；看 endpoint 区分 source/target，检查账号、网络、池容量和借用超时 |
| CONNECTION_INITIALIZE | 已借到连接，但读取/设置事务属性失败；与借不到连接是不同阶段 |
| AUDIT_CHECK / CHECKPOINT_READ | 目标审计表是否存在、结构/权限是否正确，现有检查点是否能读取 |
| SQL_POLICY / SOURCE_PREFLIGHT / TARGET_PREFLIGHT | SQL 是否符合组件限制、源查询与目标 INSERT 是否可执行、目标表与 INSERT 是否一致、参数个数是否匹配 |
| FIELD_MAPPING / FIELD_CONVERSION / KEY_PREFLIGHT | 查询结果字段、转换类型、主键列、空键和唯一性。主键唯一性检查针对源 SELECT 的结果 |
| SOURCE_COUNT | 源数量检查；同 job 恢复期间源范围和数据必须不变 |
| TARGET_PREPARE / RANGE_PLAN / RANGE_PLAN_AUDIT | 准备目标、显式清表重导、规划范围及保存计划。仅日志不会触发清表 |
| BATCH_READ / BATCH_WRITE / BATCH_COMMIT | 具体批次读取、写入、提交。结合 task、batch 和 endpoint 检查 |
| COMMIT_VERIFY | 提交发生异常后核实审计结果；无法确定时为 COMMIT_UNKNOWN，恢复连接后保持原 job/config 重新调用 |
| SEQUENCE_FINALIZE | 数据已完成，正在校准序列；失败可能是 FINALIZATION_FAILED，修复后同 job 仅重试收尾 |
| AUDIT_UPDATE / AUDIT_FAILURE_REPORT | 更新检查点或汇总；错误发生后再次写失败状态失败会单独记录，不覆盖原始原因 |
| CONNECTION_CLEANUP | 回滚、恢复事务属性或归还连接失败 |

同一调用的首个精确失败阶段会保留到表级 ERROR。批次失败后可能出现额外的审计写入失败 WARN，排查时先看原始 ERROR。单张表失败不代表其他表已经回滚。

## 典型日志

以下省略时间戳和部分关联字段；实际日志还包含 runId、job、task、batch 和 property。

```text
INFO  Migration configuration loading started ... stage=CONFIG_LOAD ... locations=1 existingEnvironment=false
INFO  Migration configuration binding succeeded ...
INFO  Opening migration connection ... stage=SOURCE_CONNECT endpoint=source
ERROR Table migration failed ... stage=CONNECTION_ACQUIRE endpoint=source ... category=AUTHENTICATION_FAILURE ... SQLSTATE=28P01; vendor=0
ERROR Table migration failed ... stage=FIELD_MAPPING endpoint=source property=data-migration.tables[0].fields[1].source-column ... reason=Source column missing from query result ...
INFO  Table data committed ...
INFO  Sequence finalization started ... stage=SEQUENCE_FINALIZE ... enabled=true
ERROR Table migration failed ... stage=SEQUENCE_FINALIZE endpoint=target ...
INFO  Table migration completed ... status=FINALIZATION_FAILED committedRows=1000 skippedRows=0 elapsedMs=1200
```

SQLState 和厂商错误码来自 JDBC。分类只使用已知编码和异常类型；例如认证、连接、权限、表/字段不存在。`UNKNOWN` 表示现有安全信息不足，不能据此断言是密码或网络问题。池等待超时与网络连接超时也可能共享错误编码，需结合宿主池监控进一步确认。

宿主只打印 `MigrationException.getMessage()` 时，也能看到失败任务、状态和已有安全错误摘要。详细阶段看同次调用日志；`result()` 仍提供逐表结果。不建议为了诊断打印完整异常链：驱动及宿主池的异常消息可能含 SQL、URL 或秘密。

## 在 SpringApplication.run 之前启用日志

Runner 的配置加载不会初始化 Spring Boot 日志系统。`.run()` 前日志由宿主当前 SLF4J 后端控制，不能假设 migration.yml 或 application.yml 中的 `logging.level` 已经生效。宿主需提供可用的日志实现；组件不新增日志框架。

使用 Logback 的宿主可以在自己的 `src/main/resources/logback.xml`（测试时为 `logback-test.xml`）加入以下普通 Logback 配置。启动前应使用标准配置，避免依赖只有 Boot 初始化后才生效的扩展标签。

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="com.datamigration" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

也可以用 JVM 参数 `-Dlogback.configurationFile=/path/to/logback.xml` 指定宿主配置。只需关键节点时将该 logger 改为 INFO；容器已经启动后的调用也可由 Boot 的 `logging.level.com.datamigration` 控制。不需要在 `data-migration` 下添加日志参数。

[BeforeStartupDemo](../src/test/java/com/datamigration/demo/BeforeStartupDemo.java) 演示先迁移再启动宿主；在 IDE 用测试 classpath 运行其 main 即可观察顺序。[模拟真实宿主测试](manual-host-migration.md) 使用相同正式入口，真实数据库信息由使用者填写。

宿主工厂创建 DataSource 若在调用 Runner 前就失败，组件尚未获得控制权，无法为这一步产生日志；应由宿主为其工厂添加安全的阶段提示。组件自身的所有级别均不输出驱动异常消息、堆栈、SQL、参数、业务行、键边界、JDBC URL、密码或 token；宿主、连接池和驱动的其他 logger 需由宿主另行管理。

同步/异步入口的执行句柄、进度与异常通知方式见 [宿主执行入口](host-execution.md)。异步方法返回成功只表示取得句柄，不代表迁移已经成功；后台结果看 completion() 或 progress()。

## 表级核验与人工重迁

可在每表 verification 中启用数量检查及内容抽检。核验不一致记录 VERIFICATION_MISMATCH，保留目标数据、成功批次和 data_complete=true；本轮失败，下次显式调用只重试核验，通过后才执行 sequence 并标记 SUCCEEDED。查询异常记录 VERIFICATION_ERROR，同样保留现场。组件不因核验失败清表或重置检查点；需要重迁时由宿主停止迁移后手动清理对应目标及任务审计，再用原 Runner 调用迁移。配置和人工重迁步骤见 [表级核验指南](table-verification.md)。

## 每次调用打印迁移进度

宿主调用 migrate() 或 migrateAsync() 时，默认 INFO 自动输出一份内存进度快照：总体 status、finished、已确认提交/跳过数量，以及每张表的 state、totalRows、committedRows 和 skippedRows。运行中重复调用也会打印，仍返回同一个执行句柄，不启动另一轮迁移。后台执行结束时再输出最终快照。

```text
Migration progress at invocation ... status=RUNNING finished=false tablesKnown=true tables=2 committedRows=120 skippedRows=0
Table migration progress ... task=customer ... state=RUNNING totalRows=1000 committedRows=100 skippedRows=0
Table migration progress ... task=orders ... state=VERIFYING totalRows=20 committedRows=20 skippedRows=0
Migration attempt finished ... status=SUCCEEDED finished=true tablesKnown=true tables=2 committedRows=1020 skippedRows=0
```

finished=true 表示本轮已经结束，需要结合 status 区分 SUCCEEDED 和 FAILED，不代表一定成功。首次异步调用时配置可能尚未绑定，日志会显示 tablesKnown=false；表已知但尚未统计源数量时 totalRows=unknown，不把未知误报成零。计数只包含确认提交的数据，不包含正在执行但尚未提交的事务；新一轮会输出当前审计记录中的成功结果。

日志直接读取线程安全内存快照，不查询数据库、不借连接，也不启动定时轮询。需持续观察时宿主可读取 execution.progress()；上一轮结束后再次调用迁移方法会发起新一轮，并重新读取目标审计。migrate() 等待它发起的新一轮结束，migrateAsync() 立即返回新句柄。运行中重复调用两者都会立即返回当前句柄。日志由宿主后端控制，需启用 com.datamigration 的 INFO。
