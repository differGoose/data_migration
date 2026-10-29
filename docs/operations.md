# 迁移结果、故障续跑与常见问题

本篇面向实际执行迁移的人。首次接入见 [getting-started.md](getting-started.md)，参数含义见 [configuration.md](configuration.md)，主动制造故障的自动化测试见 [resilience-testing.md](resilience-testing.md)。

## 执行前确认什么

确定源查询范围在整个迁移和恢复期间不变，目标停止业务写入；确认目标表和审计表已经建好。首次先选一张表的小范围试跑，保存 job-id、配置版本和人工验收结果。不要在运行中改配置或清理审计表。

多个表任务应能独立执行。表的列表顺序不是外键依赖调度器；需要严格先父表、后子表时，由宿主分阶段执行并管理好各阶段配置。迁移框架不自动关闭约束或触发器。

## 状态代表什么

| TableResult.status | 含义 | 操作者下一步 |
| --- | --- | --- |
| SUCCEEDED | 该表任务及其收尾已完成 | 核对 committed/skipped 与业务验收 |
| FAILED | 该表任务失败，可能已有成功事务块 | 修复原因后保持原 job-id 续跑 |
| COMMIT_UNKNOWN | 数据库提交结果暂时无法确认 | 恢复连接后用原 job-id 核实，不能换新 job 盲目追加 |
| FINALIZATION_FAILED | 数据阶段完成，但序列等收尾失败 | 修复收尾原因后用原 job-id 重试 |

一张表失败不表示其他表全部回滚。引擎会等待各表结束，通过 MigrationException.result() 返回逐表结果；应用应把这次调用报告为失败或部分完成。配置、Bean 或连接池初始化错误也可能以其他异常形式抛出。

## 网络中断后怎样续跑

1. 保留目标部分结果和审计表，记下原 job-id；确认旧宿主进程已经停止。
2. 恢复网络/数据库服务，确认驱动超时及账号权限可正常工作。
3. 保留源数据范围、两端连接身份、任务名、SQL、字段、键及关键策略。
4. 用原配置重启宿主，再调用 migrate()。有键任务继续未成功范围，已成功范围不会重复插入。
5. 再核对目标数据和审计汇总。已成功任务用原 job-id 再调用，不会重新全量复制。

无键任务失败时整表目标事务回滚，下次重新读取整表。它仍需要同一恢复身份，但没有有键表的分块续传能力。

数据库恢复后没有宿主调用，框架不会自己启动下一轮迁移；有限瞬时重试耗尽后会将错误交回宿主。

## 在目标库查看进度

以下查询示例使用默认审计表名；改过 schema/table-name 时请替换。job-id 替换成你的实际值，查询只用于查看：

```sql
SELECT task_name, state, total_rows, committed_rows, skipped_rows,
       processed_rows, retries, updated_at
FROM migration_audit
WHERE job_id = 'customer-import-20261029' AND batch_no = 0;

SELECT task_name, batch_no, state, expected_rows, committed_rows,
       skipped_rows, error_code
FROM migration_audit
WHERE job_id = 'customer-import-20261029' AND batch_no > 0
ORDER BY task_name, batch_no;
```

batch_no=0 是表汇总，正数是事务块，不要把汇总和批次一起 SUM，否则重复计算。审计库内部状态可能比 API 状态更细，不能简单将所有非 SUCCEEDED 都理解为没有写入。保留 job-id `__table_lock__` 的记录用于执行锁，不是业务任务。

审计键边界可能包含业务主键，不应公开导出。错误分类是脱敏信息；保留现场排查，不要手工把 state 改成成功，也不要删除锁行来绕过仍在运行的迁移。

## 常见现象怎么处理

| 现象 | 常见原因与检查顺序 |
| --- | --- |
| 宿主启动了，但没有迁移 | 组件不自动执行迁移；确认 main 中是否在 .run() 前调用 Runner，或容器内业务是否实际调用 migrate() |
| 找不到 MigrationService Bean | 确认依赖在运行 classpath，宿主启用 Boot 自动配置；基线是 Boot 4.1.1 |
| 找不到驱动类 | 驱动须由宿主添加；本组件测试依赖不传递为宿主运行驱动 |
| 找不到 migration-local.yml | 检查进程工作目录和 spring.config.import；IDE、容器的相对目录可能不同 |
| 配置缺失、字段无法绑定 | 对照模板检查拼写、环境变量和缩进；组件严格拒绝未知迁移配置字段 |
| 连接预算不足 | 按同时执行的表组合计算 source 和 target 连接数，或降低表/worker 并发 |
| 重复键错误 | 检查是否对非空目标换了新 job-id，或目标有外部写入；不要直接提高 skip-limit 掩盖问题 |
| 指纹不匹配 | 对比原 job 的 URL/用户名、SQL、映射、键和关键策略；恢复原配置，或规划独立的新任务 |
| 锁获取失败 | 检查是否有另一宿主正在处理同一目标表；确认它结束并释放数据库事务后再执行 |
| 密码解密/IAM 建连失败 | 先核对 宿主 DataSource 工厂，再独立验证解密/IAM、账号权限和 TLS；见宿主数据源文档 |
| 行数看起来对，但内容不对 | 行数不是内容校验；按唯一键抽样或做完整字段校验，检查转换类型和源范围是否变化 |

## 想重新从零迁移怎么办

先区分“续跑”和“全新一轮”。续跑保持同 job-id；全新一轮使用新 job-id，并明确目标原有数据怎么办。APPEND_RESUME 不清目标，也不按已有目标行自动去重。

整张目标表都可重做时，才考虑 CLEAR_AND_REIMPORT，它使用 DELETE 清整张目标表。源 SELECT 只选了一个范围，并不限制目标 DELETE 范围。业务约束、外键、触发器与回滚成本应事先确认；DEV 恢复测试不允许此模式。

不要通过随意删除审计记录制造“新的一轮”：原目标数据还在，新的执行者将失去可靠的已提交信息。
