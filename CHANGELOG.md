# Changelog

## Unreleased

- 将最低运行和编译版本调整为 Java 17，Spring Boot 版本保持 4.1.1。
- 替换 Java 21 集合 API 和线程池自动关闭，保留等待任务结束、取消及中断状态恢复语义。
- 同步测试、文档、Java 17 Docker 示例；CI 增加 Java 17 / 21 验证矩阵。
- 在真实 JDK 17.0.16 上通过 179 项 UT、10 项 E2E 和覆盖率门槛。

## 0.1.0-SNAPSHOT — 2026-10-28

初始开发版本，尚未发布到 Maven Central。

- 提供可嵌入宿主的单模块 Spring Boot 4.1.1 / Java 21 JDBC 迁移组件和同步方法入口。
- 支持 YAML 连接、连接池、映射、两级并发、跳过/重试及审计配置。
- 有键表按范围保存事务检查点并支持恢复；无键表采用整表事务。
- 提供显式清除重导、序列收尾、BLOB/CLOB 流式写入及目标表执行锁。
- 提供双 H2 / 10,000 行 E2E、TLS 正反例、断连与进程重启恢复测试。
- 提供 DBeaver 本地指南、Oracle/PostgreSQL TLS 与 ECS 接入说明；IAM 自动刷新及真实云端联调仍待完成。
