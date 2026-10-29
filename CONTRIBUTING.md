# 贡献与提交约定

仅支持和验证 JDK 17，需要 JDK 17（含 keytool）和 Maven 3.9+。本项目为单模块普通 JAR，包名 com.datamigration，Maven 坐标 com.datamigration:data_migration。

## 本地检查

```sh
mvn -B -ntp clean verify
git diff --check
git status --short
```

Surefire 执行 UT，Failsafe 执行 *IT；JaCoCo 只统计 UT，行/分支覆盖率均要求至少 85%。测试使用合成数据及独立 H2 数据库，TLS 用例临时生成证书。报告在 target/surefire-reports、target/failsafe-reports、target/site/jacoco-ut。

## 提交范围

提交 src、pom.xml、配置模板、docs、构建工作流及维护文档。每个修复应附能复现实际问题的测试，并同步涉及的配置示例或说明。不要通过降低覆盖率门槛、跳过失败测试来通过检查。

不提交以下内容：

- target、IDE 配置、日志、JAR/压缩包和本机绝对路径配置。
- H2 数据文件、真实数据导出、生产审计内容和完整错误日志。
- 密码、访问令牌、AWS 凭据、私钥、证书信任库及填有真实连接信息的本地 YAML / .env 文件。

.gitignore 是兜底规则；提交前仍需审查 `git diff --cached`。示例保留环境变量占位符，测试中的 sa/空密码、test-password 仅供独立测试库和临时证书使用。

## 报告问题与边界

报告 Java/数据库/驱动版本、脱敏配置、期望结果、实际状态，以及可复现的合成数据。不要在公开 Issue/PR 上传数据库密码、token、原始业务行或含凭据的 JDBC URL。

涉及提交结果未知、检查点、清表、并发锁、跳过额度或序列的变更，要验证故障恢复不会重复插入或丢失数据。真实 Oracle TCPS、PostgreSQL、IAM、ECS 验收与本地 H2 测试分开记录。数据库连接与凭据完全由宿主 DataSource 管理，IAM token 由宿主实现，参考 docs/database-credentials.md。

测试目录和包内访问例外见 [测试索引](docs/test-index.md)。新增恢复场景请遵守 [故障恢复测试约定](docs/resilience-testing.md)，不得为 DEV 自动清理或创建业务/审计表。
