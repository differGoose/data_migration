# 使用你自己的 DataSource 做真实数据库 E2E

测试接收你提供的可用源/目标 DataSource，读取迁移规则，调用正式 `MigrationRunner`，再校验数据与审计。它不创建真实数据库、不造数、不处理数据源的创建细节。没有配置外部数据源时，main/Maven明确失败，不回退到H2。

这些方法位于本仓库 `src/test/java`，属于测试设施，不打进生产JAR。请在本仓库test classpath中运行，或将相关测试设施引入宿主测试源码。H2本地测试继续保留，和真实数据库测试不冲突。

## 方式一：直接传入你已有的两个对象

```java
import com.datamigration.support.DatabaseE2E;

var report = DatabaseE2E.run(
    sourceDataSource, 4,
    targetDataSource, 4,
    "file:./e2e-local.yml"
);
System.out.println(report.directory());
```

两个4表示各自可供迁移使用的连接容量，不会调整池大小。方法只借用/归还连接，不关闭两个DataSource；创建与关闭仍由调用方管理。

复制 [真实迁移模板](../examples/e2e/oracle-postgresql.yml) 到 e2e-local.yml，替换表名、字段、job-id及查询范围。直接传对象时，删除模板中的 e2e.configuration-classes 占位项（无需提供配置类）；连接地址、账号等不需要再给这个方法。

方法执行“迁移 → 校验 → 同配置同job再次执行 → 校验数据与审计未重复累计”。正常返回包含执行结果、审计计数及报告目录；迁移失败传播 MigrationException，校验失败抛断言错误，保留已有结果。修复原因后仍调用同一方法和同一配置即可恢复。

## 配置分成两部分

`data-migration` 仍是正式迁移配置，job-id、表名、batch-size、模式等含义完全相同。

```yaml
e2e:
  scenario: MIGRATION
  phase-timeout: 180s
  report-directory: target/e2e-reports
  validation:
    customer:  # 与 tables[].name 一致
      source-sql: SELECT ID, NAME, AMOUNT FROM BUSINESS.CUSTOMER ORDER BY ID
      target-sql: SELECT ID, NAME, AMOUNT FROM MIGRATION_TEST.CUSTOMER_COPY ORDER BY ID
```

source-sql 描述**预期最终数据**，target-sql 查询对应目标范围。两端列顺序与fields一致，并按同样的唯一键顺序排序。测试进行流式行数/字段摘要比对，不保存业务行。若允许跳过坏数据，source-sql应排除预期跳过的记录；迁移select-sql仍保持真实范围。审计校验使用迁移完整源范围，核对 committed+skipped 以及各成功批次合计。

不额外要求目标表为空，也不强制追加模式或skip-limit=0。新job、重复job、主键冲突、断点恢复及明确配置的CLEAR_AND_REIMPORT均由正式引擎处理。注意：该模式本来就会DELETE整张目标表；测试不会在引擎之外另做清理。源数据在执行和校验期间必须稳定。

phase-timeout约束普通E2E执行及其取消请求，查询也设置JDBC超时。调用方仍须给DataSource设置有限的连接/网络读取超时；Java无法安全强杀一个不响应中断的驱动调用，资源回收可能需要等待驱动返回。

## 方式二：main 或 Maven 运行

因为独立进程拿不到你当前JVM里的Java对象，需提供一个仅注册源/目标DataSource的Spring配置类。它只是把你已有的数据源交出来，不需要实现新的接口。示例见 [配置类模板](../examples/e2e/ExternalDataSourcesConfiguration.java)，替换其中 YourExistingDataSources。数据源Bean必须带 `@MigrationSource(capacity=...)`、`@MigrationTarget(capacity=...)`，各一个；其他无标记DataSource不会被选中。

配置类位于test classpath；不要传整个业务应用启动类。测试只加载你显式指定的类，不默认导入H2宿主。Bean的销毁由该测试宿主上下文按你的配置负责；如果是借来的共享对象，必须显式写 `@Bean(destroyMethod="")` 禁用Spring默认的close推断；模板采用这种方式。如果该测试宿主自己创建并拥有连接池，则由该配置类明确声明 `destroyMethod="close"` 回收。

```yaml
e2e:
  configuration-classes: [your.project.ExternalDataSourcesConfiguration]
```

可以使用模板中的 E2E_DATASOURCE_CONFIG_CLASS 环境变量。其他环境变量 E2E_JOB_ID、E2E_ID_MIN、E2E_ID_MAX 由模板引用。真实目标业务表/审计表及权限需提前准备。

在仓库根目录执行（PowerShell保留引号）：

```powershell
mvn -B -ntp -Presilience-dev verify "-Dit.test=ConfiguredDatabaseE2EIT" "-De2e.config=file:./e2e-local.yml"
```

resilience-dev只添加Oracle/PostgreSQL测试驱动；不会准备数据库。默认 `mvn clean verify` 跳过这个入口，不连接真实数据库。

IDE中运行 `com.datamigration.support.DatabaseE2E.main`，程序参数填写一个或多个配置位置，例如：

```text
file:./e2e-local.yml
```

或在PowerShell通过Java运行同一个main：

```powershell
mvn -B -ntp -Presilience-dev test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt" "-DincludeScope=test"
$testClasspath = "target/test-classes;target/classes;" + (Get-Content target/test-classpath.txt -Raw).Trim()
java -cp $testClasspath com.datamigration.support.DatabaseE2E file:./e2e-local.yml
```

Java方法和main支持多个配置位置，后加载覆盖前者。Maven e2e.config使用逗号分隔位置。Spring列表整体替换，不按任务名合并。main失败时返回非零退出码，只输出脱敏失败提示。

## 普通迁移与故障场景

scenario支持MIGRATION、SOURCE_OUTAGE、TARGET_OUTAGE、APP_BEFORE_COMMIT、APP_AFTER_COMMIT。故障场景使用main/Maven配置入口，不接受将当前JVM的数据源对象传到子进程。它们复用父进程TCP代理和独立宿主JVM；每个子进程仍从外部配置类取得DataSource，通过正式Runner执行。

加载 [故障配置](../examples/e2e/fault.yml) 作为第二份文件：

```powershell
mvn -B -ntp -Presilience-dev verify "-Dit.test=ConfiguredDatabaseE2EIT" "-De2e.config=file:./e2e-local.yml,file:examples/e2e/fault.yml"
```

配置两端真实upstream地址和端口。你提供的数据源必须把实际连接路由到本机代理端口：环境属性 `e2e.source-proxy.port` / `e2e.target-proxy.port` 在创建测试数据源前由父进程分配。配置类可从Environment读取它们。建议Bean使用@Lazy，避免预读场景时尝试建立连接。测试不替你修改DataSource内部连接地址。

测试检查两端实际经过代理后才允许断网注入。代理端口只用于真实路由，不计算身份摘要。故障子进程完全结束后才启动恢复子进程；不测试跨进程并行接管。

触发任务需要单worker以确定提交顺序。trigger-commit表示本次执行的第几次业务提交，不包括审计提交；已有部分结果时继续本次剩余工作，不重置job。已经成功的触发任务直接进行恢复/重复执行验证，报告faultTriggered=false、alreadyCompleted=true；若剩余数据在触发点之前就完成，也如实报告未注入故障，不冒称已覆盖故障。

## 报告和验收边界

每次保存独立运行目录。manifest.properties和result.properties只记录运行编号、job、场景、状态、数量及验证结果，不输出SQL、口令或业务行。故障子进程日志和runtime.properties为本地控制资料，包含必要路由信息，勿公开提交；运行时SQL映射只在内存适配，不写入控制文件。

默认本地验证使用调用方准备的H2对象/服务器验证接口契约及四类故障，不代表真实Oracle/PostgreSQL已联调。真实验收必须由你提供实际可用的DataSource及稳定数据范围执行。

执行与恢复均使用 JDK 17；同 job 的迁移规则、实际数据源和源范围保持不变。普通直接对象入口同样受 JVM 全局许可约束。审计结构见 [审计设计](audit.md)。
