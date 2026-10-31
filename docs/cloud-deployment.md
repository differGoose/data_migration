# 宿主运行与部署边界

本项目是普通依赖 JAR，接收宿主提供的可用 DataSource。认证、TLS、连接池、应用容器、镜像、ECS 任务及健康检查均由宿主管理；项目不提供专用云端运行器或部署模板。Oracle/PostgreSQL JDBC 驱动继续作为运行时依赖提供。

宿主使用 JDK 17，引入组件后可通过 [通用宿主封装](../examples/HostMigration.java)调用同步或异步入口。数据源标记、连接容量和资源管理见 [接入指南](getting-started.md)与 [数据源边界](database-credentials.md)，进度和重复调用语义见 [宿主执行入口](host-execution.md)。

部署和恢复期间保持原 job-id、任务、数据源、配置及源范围不变。组件的内存协调不覆盖不同进程，宿主部署流程必须确保迁移进程不会重叠，并在关闭数据源前等待迁移清理结束。

目标业务表、审计表及所需序列由使用者预先准备。本地 H2 验证只使用合成数据；真实 Oracle/PostgreSQL 数据源的连接验证和业务验收由使用者完成，入口见 [真实数据库 E2E](real-database-e2e.md)。
