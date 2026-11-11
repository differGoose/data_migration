# 数据迁移项目讲解与 Review 指南

配套的[项目流程图](project-overview.svg)用于开场介绍。本文用于理解实现和带着同事查看代码，按一次调用的执行顺序组织。

## 1. 开场怎样介绍项目

这个组件接收调用方提供的源、目标 DataSource，以及 YAML 中的迁移规则，将源查询结果按字段映射写到目标表。它支持多表并发和有键表的批次并发，在目标库保存迁移审计。中断后再次调用时，组件根据审计判断哪些表已经完成、哪些批次需要继续。数据写完后，可以按表配置执行核验和 sequence 校准。

介绍流程图时沿着调用入口、审计判断、数据传输、核验和 sequence 收尾向下讲。图中展开成功表跳过、未完成表续跑以及核验或收尾失败的分支，连接预算、事务和重试实现由下文展开。

当前运行约定是任意时刻只有一个迁移进程。同一任务恢复期间，配置、数据源和源数据范围保持稳定。执行协调使用 JVM 内存许可，部署侧负责保证不同进程之间不会重叠执行。

## 2. 调用入口怎样工作

调用方长期持有一个 Runner，或使用 Spring 提供的单例 MigrationService。两种入口都经过 AsyncMigration 和 MigrationSession。

| 场景 | migrate() | migrateAsync() |
|---|---|---|
| 当前没有执行中的任务 | 创建新一轮并等待完成，失败直接抛出异常 | 创建新一轮并立即返回 MigrationExecution |
| 当前有执行中的任务 | 立即返回当前 MigrationExecution | 立即返回当前 MigrationExecution |
| 上一轮已经结束 | 创建新一轮，重新读取审计 | 创建新一轮，重新读取审计 |

MigrationExecution 是供调用方查询本轮执行情况的对象。progress() 返回内存中的进度快照，completion() 提供本轮最终结果或异常。runId 标识每一轮调用，job-id 和任务名参与持久化恢复。

build() 加载配置环境，首次执行时绑定并校验迁移属性，此后复用已绑定配置。关闭 Runner 会清理迁移执行资源，DataSource 的生命周期由调用方管理。

**Review 时看什么**

- 检查“判断是否已有执行”和“创建新执行”是否在同一同步保护下。
- 检查成功和失败结束后，都能创建下一轮执行。
- 检查同步调用的等待发生在同步块之外，重复调用可以及时返回。

**对应代码**

- [MigrationRunner](../src/main/java/com/datamigration/api/MigrationRunner.java)
- [AsyncMigration](../src/main/java/com/datamigration/execution/AsyncMigration.java)，重点看 start()、startSync()、run()。
- [MigrationMonitor](../src/main/java/com/datamigration/execution/MigrationMonitor.java)，重点看 reusable() 和完成信号。
- [MigrationConfiguration](../src/main/java/com/datamigration/properties/MigrationConfiguration.java)

## 3. 并发和连接容量怎样约束

一次迁移先校验配置和容量，再由默认引擎取得 JVM 共享的单许可 Semaphore。另一个 Runner 同时执行时，会因许可获取失败而报错。同一 Runner 的重复调用在入口处复用已有执行。

取得许可后，引擎按 max-concurrent-tables 调度表任务。每张有键表内部按 worker-count 调度批次。执行资源清理完成后才释放许可。

| 表类型 | 源连接需求 | 目标连接需求 |
|---|---|---|
| 有键表，W 个 worker | W + 1 | W + 1 |
| 无键表，固定一个 worker | 1 | 2 |

预算分别对源端和目标端取需求最大的前 max-concurrent-tables 张表求和。HikariCP 可自动读取 maximumPoolSize。源、目标共用同一个被识别的池时，还会检查两端需求之和。共享池中的业务连接占用由调用方预留。

**Review 时看什么**

- 表级与批次级并发是否都受配置限制。
- 预算是否覆盖协调连接，以及无键表释放源协调连接后再借给 worker 的行为。
- 异常、中断和 close() 路径是否在工作线程退出后释放许可。

**对应代码**

- [MigrationSession](../src/main/java/com/datamigration/execution/MigrationSession.java)
- [ConnectionBudget](../src/main/java/com/datamigration/execution/ConnectionBudget.java)
- [PoolCapacity](../src/main/java/com/datamigration/jdbc/PoolCapacity.java)
- [JdbcMigrationEngine](../src/main/java/com/datamigration/execution/JdbcMigrationEngine.java)，重点看 executeScoped()、keyed() 和 close()。

## 4. 审计怎样决定新跑、续跑或跳过

审计主键为 (job_id, task_name, batch_no)。batch_no=0 是表汇总，正数是事务块。表任务先借用目标连接检查审计，再决定是否借用源连接。

| 读到的审计 | 本轮动作 |
|---|---|
| 汇总为 SUCCEEDED | 返回已有结果，跳过该表的源访问、核验和 sequence |
| 汇总与批次均不存在 | 创建任务，从头迁移 |
| 数据未完成 | 继续未成功批次，并继续规划尚未规划的范围 |
| data_complete=true，汇总尚未成功 | 进入核验与收尾 |
| 批次存在，汇总缺失 | 报 AUDIT_SUMMARY_MISSING，要求人工核对 |
| 旧版 CLEANUP_PENDING 或 VERIFICATION_FAILED | 报 LEGACY_VERIFICATION_RESET_REQUIRED，要求人工处理 |

lower_key 和 upper_key 保存事务块的键范围，范围为 (lower_key, upper_key]，支持联合键。首批的空下界表示从查询范围起点开始。并行批次可以乱序完成，所以恢复会查找所有未成功批次。

若要手动重迁一张表，先停止迁移，再清理目标数据和该 job、该任务的全部审计记录。之后可用原 Runner 再次调用。只清理目标数据时，成功审计仍然会让该表跳过。

**Review 时看什么**

- 跳过成功表的判断是否早于源连接借用。
- 查待执行批次的条件是否覆盖所有未成功状态。
- 审计不完整时是否明确失败。

**对应代码**

- [CheckpointStore](../src/main/java/com/datamigration/audit/CheckpointStore.java)，重点看 find()、pending()、last()。
- [JdbcMigrationEngine](../src/main/java/com/datamigration/execution/JdbcMigrationEngine.java)，重点看 tableScoped()。

## 5. 数据怎样分批，事务在哪里提交

数据传输前会检查源字段、迁移键、目标 INSERT 参数数量等。已准备的任务恢复时还会核对源总数。这里的行数检查依赖运行约定中的源数据稳定条件。

有键表每次优先执行已有待完成批次。没有待执行批次且规划未结束时，planWindow() 最多规划 2 × worker-count 个批次。每批最多 batch-size 行，先提交范围审计，再由 worker 处理，循环直到全部范围完成。

每个 worker 读取自己的键范围，用 JdbcRows 和 ValueConverter 按映射处理字段。在目标事务中写入数据，同时通过 CheckpointStore.complete() 更新批次状态、汇总计数及跳过额度，最后提交同一个连接。

| 配置或机制 | 影响 |
|---|---|
| batch-size | 有键表的事务块行数，同时用于 fetch 提示和 JDBC batch 行数上限 |
| max-batch-bytes | 使 JDBC batch 提前执行，事务仍在块结束时提交 |
| max-lob-size | 限制单个 LOB，流式 LOB 会使用相应写入路径 |
| 无键表 | 单 worker、一个整表事务，batch-size 控制读取和 JDBC batch |

APPEND_RESUME 追加写入并依靠审计续跑。CLEAR_AND_REIMPORT 在首次准备时清目标表。有键表将清表和 prepared 标记一起提交，续跑跳过再次清表。无键表将清表、所有写入和成功检查点放在整表事务中，失败后整体回滚。

**Review 时看什么**

- 业务数据与成功检查点是否使用同一个目标连接提交。
- 批次行锁如何避免重复执行，汇总行锁如何保护并发计数和跳过额度。
- executeBatch() 和 commit() 的触发位置是否符合上表。
- 尾批、联合键、LOB 和类型转换异常的处理是否完整。

**对应代码**

- [JdbcMigrationEngine](../src/main/java/com/datamigration/execution/JdbcMigrationEngine.java)，重点看 preflight()、planWindow()、transferScoped()。
- [JdbcRows](../src/main/java/com/datamigration/jdbc/JdbcRows.java)
- [CheckpointStore](../src/main/java/com/datamigration/audit/CheckpointStore.java)，重点看 completeScoped()。
- [SqlSupport](../src/main/java/com/datamigration/sql/SqlSupport.java)

## 6. 失败、重试和提交结果未知怎样处理

提交前失败时，先尝试回滚。确认回滚后，符合瞬时错误分类且有剩余额度的异常会有限重试，等待时间按配置退避。支持跳过坏数据的有键表可以将失败批次改为行级隔离重试，并通过保存点跳过允许的数据错误。

commit() 抛异常后，引擎使用新目标连接加锁查询该批次审计。已成功则确认本批已提交，未成功则按未提交处理。查询本身也失败时，本轮返回 COMMIT_UNKNOWN，等待后续显式调用重新判断。

表失败后会尝试保存安全错误码及状态。若数据库仍不可用，审计记录失败会另记日志，执行结果保留原始失败。其他表可以继续独立执行，最终汇总失败表并抛 MigrationException。

**Review 时看什么**

- 重试前是否已经确认事务回滚或提交结果。
- 提交核实是否加行锁，以等待原事务完成。
- 失败状态写入再次出错时，是否保留最初原因。

**对应代码**

- [JdbcMigrationEngine](../src/main/java/com/datamigration/execution/JdbcMigrationEngine.java)，重点看 transferScoped() 的异常处理。
- [SqlErrors](../src/main/java/com/datamigration/execution/SqlErrors.java)
- [MigrationException](../src/main/java/com/datamigration/api/MigrationException.java)

## 7. 核验和 sequence 怎样收尾

数据完成后记录 data_complete=true。启用 verification 的表进入 VERIFYING。

数量核验重新查询源 SELECT 的 COUNT 和目标整表 COUNT，两者都要等于审计中的 committed_rows。启用核验要求 skip-limit=0，目标整表计数也意味着需要明确目标中已有数据的影响。

内容核验的抽样条数为向上取整的源总数 × sample-percent / 100。实现会扫描两端映射字段，源值按目标类型转换并规范化，计算行摘要并写入临时文件进行外部排序。它在源端随机选择固定数量的样本，比对样本所属摘要在两端的出现次数。因此抽样比例降低的是被比较的内容范围，两端扫描和摘要计算仍覆盖全部查询结果。

核验不一致记录 VERIFICATION_MISMATCH，查询等异常记录 VERIFICATION_ERROR。目标数据和成功批次保留，下次从核验继续。

核验通过后按配置执行 sequence 校准。实现读取目标表目标列的 MAX，并检查目标 sequence 的当前状态，仅在需要时向前推进。PostgreSQL 使用 setval，将下一次取值推进到已迁移最大值之后。当前支持步长为 1、非循环的 sequence，目标列为空时直接结束校准。

sequence 或其他收尾失败时记录 FINALIZATION_FAILED。下一轮根据配置重新核验和收尾。全部通过后，提交表汇总 SUCCEEDED。

**Review 时看什么**

- 核验范围是否与迁移 SQL、映射和目标表使用方式一致。
- 数值、时间、NULL、LOB 的规范化是否符合预期。
- 抽样的概率性质以及全量扫描、临时磁盘的成本是否被接受。
- sequence 的推进条件是否保证已有较大值不会被降低。

**对应代码**

- [TableVerifier](../src/main/java/com/datamigration/verification/TableVerifier.java)
- [DigestFiles](../src/main/java/com/datamigration/verification/DigestFiles.java)
- [DatabaseDialect](../src/main/java/com/datamigration/dialect/DatabaseDialect.java)

## 8. 怎样组织一次组内分享

建议用 5 分钟讲项目流程图，再用 15～20 分钟沿本文第 2～7 节看关键代码。每一节先讲预期行为，再看实现条件和异常分支。

最后结合[测试索引](../docs/test-index.md)选择证据。入口看同步/异步交叉调用和重复执行测试，事务看回滚和提交未知测试，恢复看独立 JVM 与网络故障测试，收尾看核验失败重试和 sequence 恢复测试。

Review 记录可以使用下表，记录同事提出的问题和实际结论。

| 议题 | 应确认的行为 | 讨论结论或后续行动 |
|---|---|---|
| 调用和生命周期 | 运行中复用任务，结束后重新读审计，连接由调用方管理 | |
| 并发与容量 | 表和批次并发有界，容量覆盖协调连接 | |
| 事务一致性 | 数据与检查点一起提交，提交未知可核实 | |
| 恢复 | 乱序完成的批次缺口能够补跑 | |
| 核验 | 范围、字段语义、抽样方式与扫描成本符合需求 | |
| 收尾 | sequence 向前校准，失败后能够重新收尾 | |
