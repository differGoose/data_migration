# Changelog

## Unreleased

- 删除H2 TLS专项测试、证书子进程及SSL测试开关；测试统一使用普通TCP。移除未接入实际流程的旧公开接口AuditRepository、TableTaskExecutor、TableReader、BatchWriter、RowCursor。
- 清理未读取的测试驱动/池配置和重复H2恢复资源；DEV恢复模板改为宿主提供数据源配置类。认证、TLS、连接池和部署教程收敛为职责说明，删除ECS专用入口和Dockerfile，保留通用宿主封装。

- 同步 migrate() 与新增 migrateAsync() 共用执行状态，运行中和成功后重复调用返回同一 MigrationExecution；通过 progress() 读取不可变已提交进度，通过 completion() 接收原 MigrationResult/异常。同步首次调用仍等待完成并传播异常；失败后显式重试，关闭等待清理，不关闭宿主 DataSource。返回类型由 MigrationResult 调整为 MigrationExecution。

- 增加配置、数据源、容量、表预检、数据提交及 sequence 收尾关键日志；批次细节 DEBUG、有限重试 WARN、最终失败 ERROR。跨线程关联 runId/任务/批次，保留首个安全失败阶段与 SQLState/厂商码，不输出驱动消息、SQL 或业务数据。MigrationException 消息列出失败任务及安全摘要；新增启动前日志与排错指南。

- 模拟宿主真实迁移测试增加可选手动代理模式，通过本机控制页分别切断/恢复源和目标，并用原配置、原job显式续跑；不要求e2e配置段。

- Oracle ojdbc11和PostgreSQL驱动改为默认runtime依赖，测试和宿主运行时均可直接使用，移除resilience-dev profile。

- 使用 JVM 共享单许可立即拒绝迁移重叠调用，清理完工作线程与事务后才释放；部署方保证不同进程不重叠。
- 删除数据库执行锁、所有权令牌、配置 fingerprint 及端点身份摘要；保留事务行锁和数据/检查点原子提交。新版审计表由使用者手工准备，不转换旧任务。
- 有键连接预算改为源/目标各 W+1，无键改为源1/目标2；仅支持与验证 JDK 17。

- 增加直接接收外部DataSource的DatabaseE2E、main及显式配置IT入口；报告校验迁移、审计和重复执行。
- 故障宿主改用正式Runner，移除测试强加的空目标、追加模式和零跳过限制。

- **破坏性改造**：宿主直接提供两个 DataSource，组件不再建池或获取凭据；删除旧连接配置和启动自动执行开关，不兼容旧版未完成任务。
- 新增无 Spring 容器的 MigrationRunner，支持宿主 .run() 前同步迁移；Spring 包装通过源/目标注解选择 DataSource，容量由宿主声明。
- 三项全局行数参数统一为每表 batch-size，默认1000，控制有键表事务块、JDBC batch和fetch提示；无键表保留整表事务。
- 连接借用/归还时设置并恢复事务属性，归还前回滚未完成事务；借连接错误脱敏保留SQLState和厂商码以支持有限重试。
- 改造 H2/DEV 恢复测试，保留精确提交边界、独立宿主JVM、TCP代理和脱敏执行清单；DEV由测试宿主提供DataSource。
- 重写宿主接入、配置及H2文档，提供可运行启动前示例。仅验证JDK17及行/分支各85%门槛。

## 0.1.0-SNAPSHOT — 2026-10-28

初始开发版本，尚未发布到 Maven Central。

- 提供可嵌入宿主的单模块 Spring Boot 4.1.1 / Java 21 JDBC 迁移组件和同步方法入口。
- 支持 YAML 连接、连接池、映射、两级并发、跳过/重试及审计配置。
- 有键表按范围保存事务检查点并支持恢复；无键表采用整表事务。
- 提供显式清除重导、序列收尾、BLOB/CLOB 流式写入及目标表执行锁。
- 提供双 H2 / 10,000 行 E2E、TLS 正反例、断连与进程重启恢复测试。
- 提供 DBeaver 本地指南、Oracle/PostgreSQL TLS 与 ECS 接入说明；IAM 自动刷新及真实云端联调仍待完成。
