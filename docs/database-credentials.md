# 宿主 DataSource 的责任与生命周期

组件接收两个已经可用的 DataSource。数据库地址、账号、密码解密、IAM、TLS、连接池及连接/读取超时由宿主管理；组件不提供凭据接口、不设置 JVM SSL 属性，也不覆盖宿主连接属性。

## 交给组件什么

直接将现有源、目标 DataSource 传给 MigrationRunner，并分别声明可供迁移使用的连接容量。Spring 场景使用 @MigrationSource / @MigrationTarget 标记宿主的 Bean。完整代码见 [接入指南](getting-started.md)和 [通用宿主封装](../examples/HostMigration.java)。

容量是宿主为迁移预留的连接数量，不会自动修改实际连接池大小。组件借用独立 Connection，设置迁移所需事务属性，结束未完成事务后归还；不接收宿主业务事务中的 Connection。

## 生命周期

宿主长期持有同一个 Runner 或单例 MigrationService，以共用同步/异步防重复状态。Runner.close() 停止迁移并等待执行资源清理，始终不关闭宿主 DataSource。宿主应在迁移清理结束后，再按自己的资源管理方式关闭数据源。异步执行期间不要提前退出持有 Runner 的 try-with-resources。

同步、异步、进度和完成通知见 [宿主执行入口](host-execution.md)。配置文件只描述迁移规则，不放数据库连接和凭据。

## 连接问题

先在宿主独立验证 source.getConnection() 和 target.getConnection() 是否可用，再检查迁移配置。认证或 TLS 失败由宿主的数据源配置和实际驱动处理；组件提供端点、阶段及安全错误码辅助定位，见 [日志与排错](logging-and-troubleshooting.md)。不要输出原始口令、token、完整 URL 或驱动异常链。
