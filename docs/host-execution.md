# 同步、异步调用与迁移进度

宿主长期持有同一个 `MigrationRunner`，或注入单例 `MigrationService`。组件内部处理重复触发，不需要宿主再维护 AtomicBoolean、迁移线程池或运行锁。两种入口共用执行状态。

## 两个入口的区别

| 调用时的状态 | migrate() | migrateAsync() |
| --- | --- | --- |
| 尚未执行 | 启动一次迁移并等待结束，成功后返回句柄；失败向调用方抛异常 | 启动一次后台迁移，立即返回句柄 |
| 正在执行 | 立即返回同一个句柄，不等待、不重复执行 | 立即返回同一个句柄，不重复执行 |
| 已经成功 | 返回原成功句柄，不借连接、不重新执行 | 返回原成功句柄，不借连接、不重新执行 |
| 上轮失败且清理已结束 | 创建新句柄，按审计恢复并等待结束 | 创建新句柄，后台按审计恢复 |

**返回类型调整：** `MigrationService.migrate()` 和 `MigrationRunner.migrate()` 现在返回 `MigrationExecution`，不再直接返回 `MigrationResult`。同步首次调用仍保留异常传播语义。原来的 `migration.migrate().tables()` 需改用下面的完成结果接口；不能假设每次同步调用返回都代表完成，因为重复触发可能返回正在运行的句柄。

防重复只在同一个 Runner/Service 实例内合并触发。不同 Runner、不同 Spring Context 的并发执行仍受 JVM 全局互斥限制，冲突执行会失败，不会把另一个任务当作本次任务返回。不同进程之间没有协调，部署方仍须保证迁移不重叠。

## 宿主如何调用

可直接复制 [HostMigration.java](../examples/HostMigration.java) 作为宿主封装。宿主只创建一次 Runner，将其保留为服务成员。两端 DataSource 由宿主提供，容量按迁移并发预算填写：

```java
private final MigrationRunner migration;

public HostMigration(DataSource source, DataSource target) {
    migration = MigrationRunner.builder()
            .source(source, 4)
            .target(target, 4)
            .configLocations("classpath:migration.yml")
            .build();
}

public MigrationExecution triggerAsync() {
    return migration.migrateAsync();
}

public MigrationExecution triggerSync() {
    return migration.migrate();
}
```

这段是宿主服务的成员和方法示例。Spring 场景下也可以直接注入 `MigrationService`，用同样的方法调用；首次配置绑定、源/目标 Bean 选择和建连在后台进行。Runner 的 `build()` 仍会加载配置文件，文件缺失等构建错误会在 build 阶段抛出。

异步先启动，再同步调用：

```java
MigrationExecution first = migration.migrateAsync();
MigrationExecution again = migration.migrate();
// 运行中或已成功时，first == again；不会创建第二轮迁移。
MigrationProgress progress = again.progress();
```

**不要每次调用都创建/关闭 Runner，也不要在提交异步任务后立即退出包含 Runner 的 try-with-resources。** `close()` 会停止迁移并等待事务、工作线程清理。宿主关闭时再关闭长期持有的 Runner；Runner 不关闭宿主 DataSource，数据源由宿主在迁移清理结束后关闭。

## 读取进度与完成结果

```java
MigrationExecution execution = migration.migrateAsync();
MigrationProgress progress = execution.progress();

System.out.println(progress.runId());
System.out.println(progress.status());
System.out.println(progress.committedRows());
System.out.println(progress.skippedRows());
for (TableProgress table : progress.tables()) {
    System.out.println(table.taskName() + ": " + table.state()
            + ", total=" + table.totalRows()
            + ", committed=" + table.committedRows());
}

execution.completion().whenComplete((result, failure) -> {
    if (failure == null) {
        // result 是原来的 MigrationResult，可读取 result.tables()。
    } else {
        // 异步异常由完成通知交给宿主；结合组件安全日志定位。
        // 不要直接输出原始驱动异常链、SQL、密码或 token。
    }
});
```

`progress()` 返回线程安全的不可变快照，不查询数据库、不借连接，也不触发重试。保存旧快照不会随后台变化；每次重新调用 `execution.progress()` 取得最新状态。轮询应使用该方法；失败后再次调用 migrate/migrateAsync 表示显式要求重试，而不是单纯查询。

| 字段 | 含义 |
| --- | --- |
| runId | 本轮执行编号，与默认引擎结果及日志关联；重试会产生新编号，job-id 不变 |
| status | RUNNING、SUCCEEDED 或 FAILED；FAILED 的细分结果在表状态中 |
| jobId / tables | 配置尚未绑定时 jobId 可为空、tables 可为空；不是配置已成功的标志 |
| totalRows | 每张表的源总数；计数或审计恢复前为 null，不能显示成 0% 或 100% |
| committedRows / skippedRows | 已确认提交的写入/跳过数量，不包括 JDBC execute 后尚未提交的数据 |
| state | PENDING、PREFLIGHT、RUNNING、FINALIZING，或原 TableResult 的最终状态 |
| sanitizedError | 安全错误码；不含驱动消息、SQL、连接地址和业务数据 |

进度按事务提交更新，不是逐行实时统计。无键表保留整表事务，所以执行过程中已提交行数为0，整表提交后才变化。所有行处理完但仍在 sequence/审计收尾时，状态仍为 RUNNING，表状态为 FINALIZING；只有最终成功才是 SUCCEEDED。提交结果未知时不会把未经确认的写入当作已提交进度。

已知显示限制：目标数据库中断、失败收尾也无法读取审计时，失败快照中的已提交/已跳过计数可能回退为0。这不修改数据库中的数据或检查点；恢复连接后仍按原审计续跑，不能仅凭该失败快照判断需要从头迁移。

如果宿主在某处确实需要等待当前句柄完成，可以自行选择：

```java
MigrationResult result = execution.completion().toCompletableFuture().join();
```

`join()` 失败时抛 CompletionException，其 cause 为原始失败；其中 MigrationException 仍保留 result()。同步首次调用 migrate() 直接传播原异常。取消 completion() 派生出来的 Future 不会取消迁移；停止迁移使用 Runner.close()/关闭对应 Spring Service。同步首调线程被中断时，会请求终止本轮，等待清理并保留中断标志。

## 失败恢复与重启

同实例成功结果会保留在内存中。进程重启、重新创建 Runner 后，仍按目标审计恢复成功表及未完成批次；内存进度不代替审计。恢复保持原 job-id、任务、数据源、配置和源范围不变。失败后只有显式调用才开启下一轮，不新增后台自动恢复或无限重试。

自定义 MigrationEngine 仍使用内部同步 SPI，由后台入口调用；若未接入进度观察器，表级计数在引擎返回最终结果后更新。本文实时提交进度针对默认 JdbcMigrationEngine。
