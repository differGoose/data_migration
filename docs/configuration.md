# 配置怎么填，以及这些数字怎样一起生效

完整带注释模板在 [examples/migration.yml](../examples/migration.yml)，默认值总表在 [README](../README.md#配置和校验时机)。本篇解释容易混淆的概念；完整首次接入流程在 [宿主接入指南](getting-started.md)。

## job-id、任务名、runId 的区别

| 名称 | 谁设置 | 作用 |
| --- | --- | --- |
| `data-migration.job-id` | 配置者 | 标识一次需要持久化恢复的迁移工作；故障重启不变 |
| `tables[].name` | 配置者 | job 下的一张表任务身份；恢复时不变 |
| `MigrationResult.runId()` | 引擎生成 | 本次方法调用的标识；再次调用会生成新值 |

例如 customer-import-20261029 故障三次、调用四次，仍然是同一个 job-id，可以有四个不同 runId。job-id 不应每次启动随机生成，否则无法找到原检查点。不同业务范围的新一轮迁移应规划新 job-id，并确认目标数据是否允许追加。

## SELECT、INSERT、fields 如何对应

假设源字段叫 CUSTOMER_NAME，目标字段叫 full_name：

```yaml
select-sql: SELECT ID, CUSTOMER_NAME AS NAME FROM BUSINESS.CUSTOMER ORDER BY ID
insert-sql: INSERT INTO customer_copy(id, full_name) VALUES (?, ?)
primary-key: [ID]
fields:
  - {source-column: ID, target-jdbc-type: BIGINT}
  - {source-column: NAME, target-jdbc-type: VARCHAR}
```

第一个 `?` 对应 fields 第一项 ID，第二个 `?` 对应 NAME。`source-column` 使用查询结果的列标签，所以本例用别名 NAME，不是 CUSTOMER_NAME。`target-jdbc-type` 是 Java JDBCType 名称，表示如何转换并绑定参数，不会替你修改目标表结构。

`target-table` 必须填写实际目标表，与 INSERT 一致，用于预检、锁及重导操作；它不是一个说明性标签。表名只支持普通名称或 schema.table；不要直接复制含特殊字符、复杂引号的业务对象名。INSERT 为单行 VALUES 模板，使用 `?`；SELECT 不接受运行时 `?` 参数，固定筛选条件需写入可信配置。当前不支持 CTE 或锁定 SELECT，不能把任意 SQL 脚本放进这两个字段。

## primary-key 是否一定等于数据库主键

它指源查询结果中稳定、非空、唯一的键列，可为数据库主键，也可为满足这些条件的业务唯一键。引擎会验证非空与唯一性。联合键写成 `[TENANT_ID, ID]`，顺序必须在恢复期间保持一致。源数据在迁移及恢复期间必须稳定，唯一键本身不等于一致快照。

没有可用键时写 `primary-key: []`、`worker-count: 1`。此时整张表只有一个目标事务；中断后从头重新读取，不具备表内分批续传，也不允许跳过坏数据。大表应优先设计稳定唯一键。

## fetch-size、batch-size、commit-size

以有键表 `fetch-size=1000`、`batch-size=200`、`commit-size=1000` 为例：

1. fetch-size 给源 JDBC 驱动一个读取提示，实际网络预取行为由驱动决定。
2. 目标每最多 200 行执行一次 executeBatch；这一步还没有提交事务。
3. 最多处理 1000 条源记录后统一提交目标事务与成功检查点，常见情况是 5 次 batch 后 1 次 commit。

字节限制、LOB 和最后一个不足整批的范围会影响实际批次大小。无键表即使 commit-size 写 1000，也仍整表一次 commit。坏数据被允许跳过时，commit-size 计入处理过的源记录，目标插入行数可能少于该值。

## 表并发、worker 与连接池容量

`max-concurrent-tables` 控制同时跑几张表；每张表的 `worker-count` 控制该表内部并发处理几个事务块。它们不是一个参数。

| 单表类型 | 源连接预算 | 目标连接预算 |
| --- | --- | --- |
| 有键表，W 个 worker | W + 1 | W + 2 |
| 无键表，1 个 worker | 1 | 3 |

一张有键表 W=1，需要源池至少 2、目标池至少 3；两张这样的表同时执行，需要源池至少 4、目标池至少 6。目标额外连接承担协调和锁，不能只按 worker 个数配置。引擎在执行前检查并发任务组合的预算，容量不足会拒绝执行。先用一表、一 worker 跑通，再增加并发。

`pool.connection-timeout` 是等池中连接的时间，不能代替数据库网络连接超时、读超时或 SQL 超时。`max-lifetime` 控制连接回收，不表示单次迁移最多允许跑多久。

## APPEND_RESUME 与 CLEAR_AND_REIMPORT

默认 APPEND_RESUME 保留目标已有内容，并按本 job 检查点恢复；它不自动比对已有业务行、不做冲突更新，也不是 CDC。新 job 对同一目标再追加可能遇到重复键。

CLEAR_AND_REIMPORT 显式允许 DELETE 目标表。它不只删除 SELECT 对应的源范围，使用前必须确认整张目标表都可被清除。恢复原 job 不会重复清除已经准备好的有键任务；需要完整重导时使用新 job-id。DEV 故障测试框架仅允许 APPEND_RESUME。

## 跳过、重试、序列和列表覆盖

- skip-limit 是每个 job、每张表累计允许的行级数据错误数，跨恢复不清零；网络、权限和 SQL 结构错误不能靠它忽略。首次试跑建议 0。
- transient-retry-count 不含初次尝试；仅对可归类的瞬时故障，在确认回滚或未提交后有限重试。不是一直等数据库恢复。
- sequence 校准在业务数据提交之后执行，只处理显式配置的命名序列，不复制源库缓存水位。失败可能出现“数据已迁移、序列收尾失败”，见运维指南。
- 多个 YAML 叠加时，Spring 列表整体替换。后面的 tables 只写一项时，不会自动保留前面文件里的其他表；fields、primary-key、configuration-classes 也要按完整列表填写。

恢复时优先保留全部原配置。修改 SQL、字段、键、端点身份和关键策略会触发指纹不匹配；允许调整的性能参数及实现细节见 [架构说明](architecture.md)。
