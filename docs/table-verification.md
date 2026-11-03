# 每表完成后的数量核验与内容抽检

核验是正式迁移流程的一部分，不需要测试专用的 `e2e` 配置，也不需要另外调用校验 API。默认关闭；每张表可独立启用并设置比例。迁移数据提交后，先核验，随后校准 sequence，最后才把表汇总标记为 `SUCCEEDED`。同步和异步入口行为相同。

## 如何配置

以下为需要合并到完整迁移配置中的片段；其余字段见 [完整模板](../examples/migration.yml)。

```yaml
data-migration:
  job-id: customer-import
  strategy:
    skip-limit: 0
  tables:
    - name: customer
      target-table: customer_copy
      primary-key: [ID]
      worker-count: 1
      batch-size: 1000
      verification:
        enabled: true
        sample-percent: 10
      select-sql: SELECT ID, NAME FROM business.customer ORDER BY ID
      insert-sql: INSERT INTO customer_copy(id, name) VALUES (?, ?)
      fields:
        - {source-column: ID, target-jdbc-type: BIGINT}
        - {source-column: NAME, target-jdbc-type: VARCHAR}
```

`enabled` 默认 false。开启后 `sample-percent` 必须填写，取值大于 0 且不超过 100，允许小数，例如 0.5。另一张表可配置 20 或 100，不受此表影响。只要启用的迁移表中有一张开启核验，公共 `skip-limit` 就必须为 0。

开启核验的 INSERT 必须明确列清单，每列对应一个普通 `?`，列数与 fields 一致，列名使用普通字母/数字/下划线标识符，不支持引用列名。不支持 `UPPER(?)`、常量、数据库生成表达式等写法；在执行迁移 SQL 前报错。触发器改变这些字段也会参与实际内容比较，不会被当成预期转换。目标额外的默认值列不参与比较。

## 怎么比对

1. 对原 `select-sql` 统计源行数，对整张目标表执行 COUNT，并与审计的成功写入数量比较。
2. 数量相同后，随机抽取精确数量的源记录。样本数为 `ceil(源总数 × sample-percent / 100)`：101 行的 10% 抽 11 行，非空表至少 1 行，空表为 0，100% 全量检查。
3. 按 fields 定义的顺序和目标 JDBC 类型统一源值，比较迁移的所有字段。宿主的自定义 ValueConverter 与迁移时一样只作用于源标量值一次，不再次转换已写入目标的值；源 LOB 沿用迁移的流式路径。数值忽略无意义的小数尾零，有时区时间按同一时间语义归一；NULL、空字符串及空二进制各自区分。字段转换和实际目标值有任何差异，都不视为相同。
4. 使用 SHA-256 行摘要和重复次数进行比较，不依赖两端返回顺序。抽中某种内容时，会比较这种内容在两端出现的总次数，因此无主键表和重复记录也适用。

源端、目标端都会扫描迁移字段，摘要采用临时文件分块排序，LOB 流式计算。**10% 是内容抽检比例，不代表只读取 10% 的数据库数据**；磁盘需要容纳两端摘要和排序临时文件，进程正常退出、异常或取消时会清理，强制杀进程后可由宿主运维清理系统临时目录中遗留的 `migration-verification-*` 目录。文件不保存原始业务行；摘要同样应按内部数据管理。

抽检不保证发现未抽中记录的错误，需要全量比对时使用 100%。每次核验重新随机抽样；源查询、配置及目标内容在迁移和核验期间必须保持稳定，禁止业务并发修改目标。验证期间顺序使用已有连接预算，不额外增加连接池最低容量。

## 不一致后会发生什么

**启用此功能即允许：确认数量或内容不一致时，删除整张目标表，包括迁移前已有的数据。请使用专用目标表。** APPEND_RESUME 不豁免这项核验清理规则；目标历史数据也计入总数。

先持久化 `CLEANUP_PENDING`，然后在同一个目标事务中执行普通 DELETE、删除当前 job/任务的正数批次检查点、重置汇总计数和阶段标志，最终保留汇总状态 `VERIFICATION_FAILED` 及原因。本次调用失败；不会自动无限重试或立即重迁。其他目标表不因该表失败被清空。

下次显式调用原 Runner/Service 的 migrate 或 migrateAsync，会从头迁移失败表；重新创建 Runner/JVM 也依赖相同审计恢复。不要删除审计来触发重迁。其他历史 job 的审计不会改写，清表后这些历史记录不能用来证明当前目标数据仍然存在，不能再复用已完成的旧 job 做数据存在性检查。

DELETE 不使用 TRUNCATE，也不添加 CASCADE；组件只对失败目标表发出 DELETE。数据库已有的外键级联、DELETE 触发器及权限仍遵循数据库行为，宿主应提前确认它们的影响。若约束或权限阻止清理，保留待清理状态和现场；修复后再调用。DELETE、检查点重置任一失败都回滚，不会只删数据却保留成功批次。提交响应丢失时依靠汇总状态确认是否已清理。

## 审计状态与重试

审计表结构不变，以下状态写入现有 state，错误码写入 error_code；汇总仍是 batch_no=0。

| state | 含义 | 下次显式调用 |
| --- | --- | --- |
| VERIFYING | 数据已提交，正在核验 | 重新核验，再做 sequence 收尾 |
| VERIFICATION_ERROR | 网络、超时、转换或临时文件等导致无法完成核验，尚未确认不一致 | 保留数据与批次，只重试核验及收尾 |
| CLEANUP_PENDING | 已确认不一致，但清理未被确认成功 | 先清理并重置，再从头迁移 |
| VERIFICATION_FAILED | 已确认不一致且整表已清理 | 从头规划和迁移 |
| FINALIZATION_FAILED | 核验通过后，sequence 等收尾失败 | 重新核验，再重试收尾，不重插数据 |
| SUCCEEDED | 数据、核验和 sequence 均完成 | 直接返回已有结果，不再次核验 |

确认不一致的错误码为 `VERIFICATION_COUNT_MISMATCH` 或 `VERIFICATION_CONTENT_MISMATCH`。网络/查询异常不会仅因无法核验就删除数据。日志及公开结果不含业务行、键值或摘要；通过 runId、任务名、阶段、安全错误码定位。异步宿主通过 execution.progress() 查看 VERIFYING、CLEANUP_PENDING 等状态，通过 completion() 接收最终失败。

查询审计示例：

```sql
SELECT job_id, task_name, state, error_code, total_rows,
       committed_rows, prepared, data_complete, planning_complete
FROM migration_audit
WHERE job_id = 'customer-import' AND batch_no = 0;
```

原本已标记成功的任务不会补做核验；不要通过修改同 job 配置要求其重新核验。需要新迁移时安排新的 job，并明确目标数据处置。
