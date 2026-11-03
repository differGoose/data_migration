# 配置含义与每表批量

本版 YAML 只包含迁移规则；连接与池由宿主 DataSource 负责。完整入门见 [接入指南](getting-started.md)，模板见 [migration.yml](../examples/migration.yml)。旧的全局批量、连接及启动开关配置已移除，填写旧字段会明确报错，不提供兼容转换。

## 身份与恢复

job-id 是整轮工作的稳定编号，同一配置中各表共用它；tables[].name 区分表任务。runId 由每次 API 调用生成，不是恢复身份。相同 job-id/name 重试恢复原进度，换新 job 不会自动清理目标或去重。

恢复期间保持两个实际数据库、命名空间、迁移配置与源数据范围不变。组件不计算端点身份或配置摘要，不自动检测配置变动；同 job 不支持修改规则后续跑。

## 唯一的行数参数：tables[].batch-size

默认 1000，必须 >=1，每表独立：

```yaml
data-migration:
  job-id: combined-import
  tables:
    - name: customer
      batch-size: 1000
      # 其余完整映射……
    - name: orders
      batch-size: 500
      # 其余完整映射……
```

有键表以该值划分事务块，同时作为源 fetch 提示、目标 JDBC batch 上限；一次 migrate 持续到所有表结束。2500 行、batch-size=1000 通常是三个事务块：1000、1000、500。允许跳过的源记录也计入块大小；写入目标的行数可能更少。

JDBC 驱动可自行决定网络预取；max-batch-bytes、LOB 和尾块也可能让实际 executeBatch 小于此值，但不会因此提前改变事务块提交边界。多个 worker 同时执行时，每个块各自拥有这个上限，并非全表瞬时合计上限。

无键表 primary-key: [] 必须 worker-count=1，仍整表一个事务；batch-size 只影响 fetch/batch，不能提前 commit。失败整表回滚，下次从头读；无键表不能跳过坏数据。

## SQL、键和字段

select-sql 是一个可信管理员提供的 SELECT，insert-sql 是单行 VALUES 模板，参数用 ?。fields 列表的第一项对应第一个问号，source-column 对应查询结果列标签（使用别名时填写别名），target-jdbc-type 使用 BIGINT、VARCHAR、DECIMAL 等 JDBCType 名称，不是修改目标表结构的指令。

target-table 必须与 INSERT 的实际目标一致；只支持普通 table 或 schema.table 标识符。primary-key 是查询结果中稳定非空唯一的列，可为业务唯一键；联合键顺序如 [TENANT_ID, ID] 在恢复时不变。SELECT 不支持 CTE、运行时 ? 参数或锁定查询。SQL 校验不代替源账号只读权限。

## 表完成后的核验

`tables[].verification.enabled` 默认 false；开启后必须配置 `sample-percent`，范围为大于 0、至多 100，支持小数。每张表独立设置。先比较源查询、目标整表和审计数量，再按向上取整的样本数量随机比对迁移字段。开启要求公共 skip-limit=0，以及显式 INSERT 列清单和普通问号参数。

**确认不一致会清空目标整张表，包含原有数据**，同时重置当前任务检查点，下次显式调用从头重迁。网络和查询异常保留现场，只重试核验。详细行为、审计状态及成本见 [表级核验](table-verification.md)。

## 并发和连接容量

同实例的同步/异步调用共用防重复状态，运行中返回同一执行句柄及进度；JVM 共享单许可阻止不同 Runner 和 Spring Context 的重叠执行，不影响一次执行内部的并发。不同进程须由部署方保证不重叠。

max-concurrent-tables 控制表并发，worker-count 控制单表块并发：

| 单表类型 | 源连接 | 目标连接 |
| --- | --- | --- |
| 有键，W 个 worker | W+1 | W+1 |
| 无键，1 个 worker | 1 | 2 |

默认使用 builder.source(source).target(target)，或不填写capacity的 @MigrationSource / @MigrationTarget。组件对启用表按每端需求取最大的 max-concurrent-tables 张合计，与实际 HikariCP maximumPoolSize 比较；两张单worker有键表同时执行至少源4、目标4，四张各2个worker并且表并发为4时至少各12，表并发为2时至少各6。同一个底层池同时作为源和目标时，还要满足两端总需求。

容量不足在借连接/执行迁移SQL前报错，例如 Insufficient source pool capacity; required=12, maximumPoolSize=10。同步首次调用直接抛出，异步通过 completion() 和安全日志交回宿主；修复宿主池大小后可以显式重试，预检会重新读取上限。不会自动扩容、调整并发或修改池属性。

自动模式支持HikariDataSource及其子类、Spring DelegatingDataSource包装链和支持标准JDBC unwrap的包装。未知池、路由包装无法确定实际目标、包装循环或无法读取正数上限时明确失败，不猜测、不默认跳过。宿主如使用无法识别的自定义/非池化DataSource，可继续选择 source(ds, capacity)/target(ds, capacity)，或注解的正数capacity；这是显式声明，不是自动读取。识别到Hikari池时，显式声明也不能掩盖更小的实际池上限。

自动检查只验证配置上限，不保证共享业务池当时的空闲连接数。宿主仍需为业务留出余量或使用专用迁移池；有共享池迁移额度时，可通过可选显式capacity限制预算。已成功的同实例重复调用仍只返回原句柄，不重新检查池或重跑迁移。

宿主负责池借用超时、JDBC 连接和读超时、连接回收及动态凭据。Runner 不关闭数据源。连接借出后设为 autoCommit=false、READ_COMMITTED，结束未完成事务后恢复原 autoCommit/隔离级别并归还。请提供干净的独立连接，不要包装正在使用的业务事务 Connection。

## 策略默认值

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| strategy.max-concurrent-tables | 4 | 同时运行的表数，须满足连接预算 |
| tables[].worker-count | 1 | 该表并发事务块数量 |
| strategy.skip-limit | 0 | 每 job、每表累计允许的行级错误，续跑不清零 |
| strategy.transient-retry-count | 3 | 初次尝试之外的有限瞬时重试 |
| strategy.retry-initial-backoff / retry-max-backoff | 1s / 10s | 正数指数退避及上限 |
| strategy.max-batch-bytes / max-lob-size | 16MB / 32MB | batch估算内存及单字段大小限制，不是 JVM 总内存限制 |
| strategy.audit.table-name | migration_audit | 预建审计表，audit.enabled 必须 true |
| tables[].mode | APPEND_RESUME | 默认追加恢复，不自动去重或更新冲突行 |
| tables[].sequence.enabled | false | 开启时显式指定命名序列及目标列，数据完成后校准 |

CLEAR_AND_REIMPORT 显式 DELETE 整张目标表，不只清源 SELECT 范围。修复后同 job 恢复不会再次执行已完成的准备清除。新一轮全量重做须新 job-id 并明确目标数据处置。

## 多文件覆盖与配置缓存

configLocations 后文件覆盖前文件，命令行优先，环境变量和 profile 按 Boot 配置规则处理。Spring 列表整体替换，tables/fields 不能按名称增量合并。首次绑定成功后配置缓存；修改文件不会让已有 Runner 热加载。

本版不接管旧版未完成检查点，旧 API 和配置不保留。审计表已删除 fingerprint 列和内部执行锁记录，详见 [审计设计](audit.md)。旧任务先用旧版本完成；使用者人工准备新版审计表，组件不自动转换或 ALTER。
