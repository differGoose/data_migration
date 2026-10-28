# 自定义数据库凭据

这份文档解决的是：**项目里拿到的可能是加密密码，迁移组件怎样使用解密后的密码连接数据库？**

这里的“宿主项目”，就是引入 `data_migration` 这个 JAR 的 Spring Boot 应用。解密代码写在宿主项目里，迁移组件通过 Spring 调用它。

先按自己的情况选择：

| 你现在有什么 | 怎样接入 |
| --- | --- |
| 已经是数据库能直接使用的密码 | 配置 `username/password` 即可，不需要提供器 |
| 配置里是密文，项目里已有解密工具或服务 | 使用下面“项目自定义解密”的步骤 |
| 密码放在密钥服务中，需要按 secret ID 获取 | 提供器调用密钥服务，返回取出的密码 |
| PostgreSQL 使用 AWS RDS IAM | 提供器生成 token，把 token 作为密码返回，见后面的 IAM 示例 |

`credentials-provider` 是**你注册的 Spring Bean 的名字**，不是加密算法名称、Java 类名或密钥。组件不自带你们项目的解密算法，也不会看到 `ENC(...)` 就自动解密；它需要你提供“怎样拿到可用密码”的代码。

源库和目标库各自通过 `credentials-provider` 指定一个 `DatabaseCredentialsProvider` Spring Bean 名称。未设置时，继续使用原有 `username/password`，空密码合法；设置后可省略密码。用户名仍必填，提供器必须返回相同用户名，避免迁移身份随凭据轮换改变。错误 Bean 名称、类型或无效返回值会失败，不回退到静态密码。

提供器在每次新建物理 JDBC 连接时执行，复用池内连接不会执行。配置和池仍为懒加载，组件不会在宿主启动时主动获取凭据。提供器需线程安全，负责自己的缓存、到期提前量及外部调用超时；Hikari 的借用超时不能代替外部服务超时。提供器 Bean 的构造也应避免网络访问。组件关闭连接池，宿主/Spring 负责关闭提供器持有的 SDK 客户端。

## 静态密码

以下均为端点配置片段，合并到现有迁移配置中：

```yaml
data-migration:
  source:
    jdbc-url: ${MIGRATION_SOURCE_JDBC_URL}
    username: ${MIGRATION_SOURCE_USERNAME}
    password: ${MIGRATION_SOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
```

## 项目自定义解密

### 1. 先看完整调用过程

假设源库的真实密码是 `示意明文`，你们的配置中心保存的是 `ENC(示意密文)`。这两个字符串只用于解释流程，不能当作实际连接密码。

```text
YAML 的 source.password = ENC(示意密文)
          ↓
迁移组件准备新建一个源库物理连接
          ↓
找到 source.credentials-provider 指定的 Spring Bean
          ↓
把源库配置 endpoint 传给 Bean
          ↓
Bean 调用项目自己的解密服务：密文 → 可用密码
          ↓
Bean 返回 new DatabaseCredentials(配置的用户名, 可用密码)
          ↓
组件把返回值交给 JDBC 驱动建立连接，连接进入连接池
```

**你只需要做两件事：配置 Bean 名称，并在宿主项目注册同名 Bean。迁移组件负责调用和建连。** 不需要修改迁移引擎，也不需要自己创建连接池。

### 2. 在宿主项目 YAML 中指定密文和 Bean 名称

下面是源端配置片段，合并到你已有的 `data-migration` 配置中；job-id、目标端、表映射等仍按原来的方式配置。

```yaml
data-migration:
  source:
    jdbc-url: ${MIGRATION_SOURCE_JDBC_URL}
    username: ${MIGRATION_SOURCE_USERNAME}
    password: ${MIGRATION_SOURCE_ENCRYPTED_PASSWORD} # 环境变量中放密文
    driver-class-name: org.postgresql.Driver
    credentials-provider: decryptedDatabaseCredentials
```

这里的 `password` 保存的是**解密输入**。因为设置了 `credentials-provider`，组件会使用提供器返回的密码建立连接，不会把这个密文直接交给数据库。

### 3. 在宿主项目添加一个配置类

下面假设你们项目已经有一个 Spring Bean `ProjectSecretService`，它的 `decrypt(String ciphertext)` 方法能把密文解成数据库密码。**这个类是示例占位名称，项目并没有内置它**，请替换成你们实际的解密服务类型、包名和方法。

```java
package com.example.application.config;

import com.datamigration.api.DatabaseCredentials;
import com.datamigration.api.DatabaseCredentialsProvider;
import com.example.application.security.ProjectSecretService; // 换成你们实际的服务
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MigrationCredentialsConfiguration {

    // Bean 名称与 YAML 的 credentials-provider 完全一致。
    @Bean("decryptedDatabaseCredentials")
    public DatabaseCredentialsProvider decryptedDatabaseCredentials(
            ProjectSecretService secrets) {
        return (role, endpoint) -> {
            String ciphertext = endpoint.password(); // YAML 中配置的密文
            String plaintext = secrets.decrypt(ciphertext); // 调用你们的解密逻辑
            return new DatabaseCredentials(endpoint.username(), plaintext);
        };
    }
}
```

这段代码中的 `(role, endpoint) -> { ... }` 是 `DatabaseCredentialsProvider.resolve(...)` 的简写实现。你可以把它理解为“组件每次需要新建连接时，会执行大括号里的代码”。

| 参数 / 返回值 | 具体含义 |
| --- | --- |
| `role` | 本次为源端 `SOURCE` 还是目标端 `TARGET` 获取凭据 |
| `endpoint` | 本次连接对应的 source 或 target 配置，不是数据库连接 |
| `endpoint.password()` | 原配置中的值；本例就是密文 |
| `endpoint.username()` | 原配置中的数据库用户名 |
| `new DatabaseCredentials(...)` | 返回给组件使用的用户名和可用密码 |

把配置类放在宿主 `@SpringBootApplication` 的扫描范围内；如果不在扫描范围，用 `@Import(MigrationCredentialsConfiguration.class)` 显式导入。`ProjectSecretService` 也必须是可注入的 Spring Bean。如果你们使用的是静态解密工具，则去掉该方法的服务参数，直接在方法体内调用你们的静态解密方法即可。

### 4. 解密算法和密钥从哪里来？

密文必须用**与加密时匹配的算法、密钥及参数**解密。迁移组件无法从一段密文猜出这些信息。

如果项目已经能解密其他配置，直接复用那套能力。如果项目还没有解密能力，需要先确定密文由谁生成、使用什么算法、密钥从哪里取得，再实现对应服务。不要把示例类当作现成解密库，也不要把 Base64 解码当作密码解密。

解密服务的实现和密钥管理都留在宿主项目。不要把密钥和密文一起写进提交到 Git 的 YAML，也不要在排查时打印解密结果。

### 5. 源库和目标库怎样分别使用？

上面的 YAML 只为源端启用解密，目标端继续按自己的配置获取密码。如果两端使用同一套解密逻辑，在两端都填写 `credentials-provider: decryptedDatabaseCredentials` 即可；组件会分别传入各自的 endpoint。两端逻辑不同就注册两个 Bean，并分别填写对应名称。需要按端点分支时，可以检查 `role`。

如果是按 secret ID 从密钥服务读取密码，可以省略端点的 `password`，在宿主独立配置里保存 secret ID，由提供器读取该配置并调用密钥服务。此时也要返回 `new DatabaseCredentials(endpoint.username(), 获取到的密码)`。

### 6. 什么时候调用，出错时怎么看？

- 每次**新建物理 JDBC 连接**都会调用提供器；从池里借用已有连接时不会再次解密。不是每条 SQL 都解密，也不是每批数据都解密。
- 配置中的用户名仍必填，返回的用户名必须与它一致；密码不能返回 `null`，允许返回空字符串供支持空密码的数据库使用。
- Bean 名称写错、Bean 没被扫描、解密抛异常或返回无效值都会失败，不会改用 YAML 密文重试连接。
- 提供器可能被并发调用，解密服务应线程安全；访问外部密钥服务时，应由服务自身设置调用超时。
- 组件会隐藏可能带有秘密的底层异常。排查顺序是：Bean 名称及扫描范围 → 解密服务是否能独立处理同一密文 → 返回用户名是否一致 → JDBC 连接配置。日志只记录安全的错误类型或内部追踪编号。

如果通过恢复测试的子进程运行这套配置，还要把配置类全名放进 `resilience.configuration-classes`，并确保它依赖的解密服务也能在子进程中注册。可在配置类上用 `@Import` 导入服务配置；详见 [恢复测试指南](resilience-testing.md)。

## PostgreSQL / AWS RDS IAM

宿主通过 AWS SDK v2 BOM 管理版本，并自行添加 `software.amazon.awssdk:rds` 及 PostgreSQL JDBC 驱动依赖；核心组件没有 AWS SDK 依赖。

```yaml
data-migration:
  target:
    jdbc-url: jdbc:postgresql://${RDS_HOST}:${RDS_PORT:5432}/${RDS_DATABASE}?sslmode=verify-full&sslrootcert=${RDS_CA_FILE}
    username: ${RDS_USERNAME}
    driver-class-name: org.postgresql.Driver
    credentials-provider: rdsIamCredentials
project:
  rds:
    region: ${AWS_REGION}
    hostname: ${RDS_HOST}
    port: ${RDS_PORT:5432}
```

以下 Bean 方法放入宿主配置类。签名 hostname 使用真实 RDS endpoint，region、port、username 必须对应所连接实例；不要用任意 DNS 别名作为签名地址。JDBC URL 不要包含用户名或密码，以免驱动的 URL 参数覆盖动态凭据。

```java
import com.datamigration.api.DatabaseCredentials;
import com.datamigration.api.DatabaseCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

@Bean(destroyMethod = "close")
DefaultCredentialsProvider migrationAwsCredentials() {
    return DefaultCredentialsProvider.builder().build();
}

@Bean
DatabaseCredentialsProvider rdsIamCredentials(
        DefaultCredentialsProvider migrationAwsCredentials,
        @Value("${project.rds.region}") String region,
        @Value("${project.rds.hostname}") String hostname,
        @Value("${project.rds.port:5432}") int port) {
    var utilities = RdsUtilities.builder()
            .credentialsProvider(migrationAwsCredentials)
            .region(Region.of(region)).build();
    return (role, endpoint) -> new DatabaseCredentials(endpoint.username(),
            utilities.generateAuthenticationToken(request -> request
                    .hostname(hostname).port(port).username(endpoint.username())));
}
```

这里每次建连生成 token，不在 YAML 保存 token。AWS token 有效期为 15 分钟，已连接会话不因 token 到期而主动关闭，不需要为此缩短连接池生命周期。ECS 使用 task role 的默认凭据链；数据库需开启 IAM、数据库用户获授对应权限、IAM 主体具备 `rds-db:connect`，并配置受信任 CA 和主机名验证。

参考：[AWS IAM 连接说明](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.Connecting.html)、[SDK v2 RdsUtilities](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/rds/RdsUtilities.html)。

## 失败与验收

提供器不得打印密码、token、完整端点或外部异常。组件将获取/建连异常替换为只包含端点角色和提供器名称的 SQLException，不携带可能泄密的原始 cause。凭据对象的 `toString()` 已脱敏，但单独调用访问器仍会得到秘密。

本地自动化测试使用 H2 和模拟提供器验证连接复用、重建、并发隔离及失败恢复。真实 IAM 需在接入环境验证首次连接、15 分钟后的新连接、池扩容、权限撤销与 TLS 校验；本地测试不代表 AWS 联调完成。
