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

核验只检查并报告，不负责修复或删除业务数据。确认数量或内容不一致时，汇总行标记 `VERIFICATION_MISMATCH`，error_code 区分数量与内容错误；本轮迁移返回失败。目标数据、正数批次记录、计数以及 `prepared`、`data_complete`、`planning_complete` 保持原样。其他表仍按独立任务规则执行。

下一次显式调用原 Runner/Service 的 migrate 或 migrateAsync，仅重新核验该表；不重新执行 INSERT、不再次执行 CLEAR_AND_REIMPORT 的准备清表，也不重置检查点。核验通过后继续 sequence 收尾，最后标记 SUCCEEDED。数据仍不一致时再次失败，不自动循环，不会因多次调用而反复清表重迁。新 JVM/Runner 也从目标审计读取相同阶段。

网络、超时、转换或临时文件等执行异常记录 `VERIFICATION_ERROR`；因为尚未确认数据不一致，也保留数据和检查点。核验失败状态写入审计再次失败时日志保留原始核验原因；已提交的 `data_complete=true` 仍使下次调用进入核验，而非重插数据。

日志报告 `Verification mismatch; preserving target data and checkpoints` 和安全错误码，不输出业务行、键值或摘要。再次调用的 INFO 进度保留已提交数量，同时用 VERIFICATION_MISMATCH 表明这些数据尚未通过核验，不能把 committedRows 达到总数当作迁移成功。

## 人工决定重新迁移

可选择先人工修复目标差异，然后直接再次调用原方法只重试核验。若认为需要从头重迁，由宿主管理删除：

1. 等待当前迁移结束，并暂停触发新迁移；保证没有其他迁移进程操作该目标。
2. 手动清理目标表，同时删除审计中对应 job_id + task_name 的**全部记录**，包括 batch_no=0 汇总和所有正数批次。只删除汇总而保留批次时，下一次调用会报 AUDIT_SUMMARY_MISSING。只清理业务数据时，已有成功审计仍会使该表跳过。
3. 建议在同一个目标数据库事务中完成业务数据和对应审计的删除；确认权限、外键及触发器影响。共享审计表不要整体清空，以免影响其他表和其他 job。
4. 用原 Runner 再次调用。它会重新读取审计，从空检查点开始迁移该表。其他仍有成功审计的任务会跳过。

以下是人工操作示例，组件不会自动执行；替换为实际目标、job 和任务名：

```sql
BEGIN;
DELETE FROM customer_copy;
DELETE FROM migration_audit
WHERE job_id = 'customer-import' AND task_name = 'customer';
COMMIT;
```

旧句柄和其他 job 的成功审计只反映各自的历史执行结果。若同时调整 SQL 或映射，清理对应检查点后创建新 Runner 读取新配置。

## 审计状态与重试

审计表结构不变，以下状态写入现有 state，错误码写入 error_code；汇总仍是 batch_no=0。

| state | 含义 | 下次显式调用 |
| --- | --- | --- |
| VERIFYING | 数据已提交，正在核验 | 重新核验，再做 sequence 收尾 |
| VERIFICATION_MISMATCH | 已确认数量或内容不一致，数据和成功批次保留 | 只重试核验，通过后继续收尾 |
| VERIFICATION_ERROR | 核验执行异常，未确认数据错误，现场保留 | 只重试核验及收尾 |
| FINALIZATION_FAILED | 核验通过后，sequence 等收尾失败 | 重新核验，再重试收尾，不重插数据 |
| SUCCEEDED | 数据、核验和 sequence 均完成 | 直接返回已有结果，不再次核验 |

确认不一致的错误码仍为 `VERIFICATION_COUNT_MISMATCH` 或 `VERIFICATION_CONTENT_MISMATCH`。没有单独保存抽检比例或通过标志，SUCCEEDED 也可能来自未启用核验的任务，需要结合原配置判断。原本已成功的任务不会补做核验。

旧版本的 `CLEANUP_PENDING`、`VERIFICATION_FAILED` 属于已移除的自动清理流程，不能解释为新版“数据已保留”。新版遇到它们会停止并返回 `LEGACY_VERIFICATION_RESET_REQUIRED`，保留原审计状态，不继续删除或自动重迁。人工检查旧版是否已经清表，然后按上面的重迁步骤成对清理目标及对应审计。

查询审计示例：

```sql
SELECT job_id, task_name, state, error_code, total_rows,
       committed_rows, prepared, data_complete, planning_complete
FROM migration_audit
WHERE job_id = 'customer-import' AND batch_no = 0;
```
