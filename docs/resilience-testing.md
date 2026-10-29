# 故障恢复测试

推荐新入口见 [真实DataSource E2E](real-database-e2e.md)：普通迁移与四类故障共用e2e配置，数据源由调用方提供。以下是原resilience入口的本地用法。

默认mvn clean verify只运行本地H2设施；真实数据库显式启用，数据库对象由使用者提前准备。源数据必须稳定。测试不额外建表、清表或造真实业务数据，正式迁移模式自身的行为不受修改。

```powershell
mvn -B -ntp verify "-Dit.test=ConfiguredResilienceIT" "-Dresilience.config=file:examples/resilience/scenarios.yml,file:examples/resilience/h2.yml" "-Dresilience.scenario=SOURCE_OUTAGE"
```

本地H2 fixture自己准备独立数据库；真实环境可使用配置的外部源/目标标记Bean，按[旧DEV模板](../examples/resilience/oracle-postgresql.yml)显式配置。更推荐e2e新模板，不需要把连接配置交给组件。

父进程持有源、目标TCP代理，关闭已有转发连接并拒绝新连接；宿主在子JVM执行。业务SQL精确匹配并在提交边界与父进程握手。网络恢复后以同job/config重启Runner，最后再次执行核对数据和审计未累计。

不要求新job空目标，不强制APPEND_RESUME、有键或零跳过额度。触发任务单worker用于确定故障顺序。无键表提交前崩溃也可恢复整表事务。新job冲突、同job完成、显式重导均由正式引擎处理；测试失败保留现场。

源验证SQL表达预期最终子集，跳过额度>0时可排除预期坏数据；完整迁移源数量另外用于核对committed+skipped。汇总与批次审计合计一致，重复执行前后的目标摘要及批次/计数一致。崩溃前后对比触发任务已经提交的目标快照，不依赖“目标最初为空”的假设。

每轮保存脱敏manifest/result；控制文件和子进程日志仅供本地诊断。已有完成任务不会为了制造故障换新job或清目标，报告会如实标记faultTriggered=false。详细字段、超时和路由方式见新版指南。

仅支持 JDK 17。父进程先确认故障宿主 JVM 结束，再启动恢复宿主；任意时刻只运行一个迁移进程。新版不包含数据库执行锁、所有权令牌或跨进程接管测试。恢复保持原配置、实际数据源和源范围不变；事务检查点和数据原子提交保证仍保留。
