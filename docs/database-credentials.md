# 宿主怎样提供数据源、解密密码和 IAM

**组件接收的已经是可连接数据库的 DataSource，不再自己读取数据库密码。** 解密、密钥服务、IAM、连接池和超时都由宿主负责。你们已经有可用 DataSource 时，直接传给 MigrationRunner，或通过源/目标注解注册即可。

## 普通密码

在宿主的工厂中创建连接池（宿主需自行添加 Hikari 和驱动依赖）：

```java
var source = new com.zaxxer.hikari.HikariDataSource();
source.setJdbcUrl(System.getenv("SOURCE_JDBC_URL"));
source.setUsername(System.getenv("SOURCE_USERNAME"));
source.setPassword(System.getenv("SOURCE_PASSWORD"));
source.setMaximumPoolSize(4);
source.setMinimumIdle(0);
source.setConnectionTimeout(10000);
```

再 `.source(source, 4)` 传入 Runner。连接池仍属于宿主，Runner 不会替宿主关闭它。源、目标各做一次。

## 自定义解密：密文在哪里变成明文

假设宿主环境变量 SOURCE_ENCRYPTED_PASSWORD 中是密文，你们已有一个解密服务 `ProjectSecretService`：

```java
// 以下方法写在宿主工厂里；ProjectSecretService 替换成你们实际的类型。
static com.zaxxer.hikari.HikariDataSource createSource(ProjectSecretService secrets) {
    String encrypted = System.getenv("SOURCE_ENCRYPTED_PASSWORD");
    String password = secrets.decrypt(encrypted);
    var source = new com.zaxxer.hikari.HikariDataSource();
    source.setJdbcUrl(System.getenv("SOURCE_JDBC_URL"));
    source.setUsername(System.getenv("SOURCE_USERNAME"));
    source.setPassword(password);
    source.setMaximumPoolSize(4);
    source.setMinimumIdle(0);
    return source;
}
```

调用顺序是：**宿主取得密文 → 宿主解密 → 宿主创建 DataSource → 交给迁移组件借连接**。这里没有组件提供的解密算法，也不需要实现组件的凭据接口。

ProjectSecretService 是占位类型，不是本仓库内置类。替换成你们已有的工具或服务；算法、密钥与加密时必须匹配。没有解密能力时，应先确认密文来源及密钥管理方式，Base64 解码不等于解密。不要把密钥和密文一起提交，不要打印明文。

若在 main 的 `.run()` 前调用，普通 Spring Bean 尚未创建，不能自动注入这个服务。应由宿主以普通 Java 工厂/SDK 客户端创建解密能力。如果你们的解密服务只能依赖完整宿主容器，则需先将解密所需的最小能力抽成可独立初始化的工厂，再使用启动前迁移入口。

若在容器内迁移，可以在宿主 @Bean 方法中注入项目解密服务，再创建带 `@MigrationSource(capacity=4)` 的 DataSource。详见 [接入指南](getting-started.md)。

## 密钥服务和 IAM：新物理连接时获取

静态密码解密后放进池适合密码在本轮有效的情况。IAM token 有时效，不能只在应用启动时生成一次，然后长时间把它当固定池密码。

建议由宿主提供一个“每次 getConnection 都获取有效凭据”的底层 DataSource，再用宿主的池包装它。下面是宿主实现示例；`AbstractDataSource` 来自宿主自行引入的 spring-jdbc，非组件扩展接口：

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.AbstractDataSource;

public final class TokenDataSource extends AbstractDataSource {
    private final String url, username;
    private final Supplier<String> token;
    public TokenDataSource(String url, String username, Supplier<String> token) {
        this.url = url; this.username = username; this.token = token;
    }
    @Override public Connection getConnection() throws SQLException {
        try {
            var properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", token.get());
            return DriverManager.getConnection(url, properties);
        } catch (SQLException | RuntimeException failure) {
            throw new SQLException("Host authentication/connection failed");
        }
    }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        throw new java.sql.SQLFeatureNotSupportedException();
    }
}
```

宿主将 `token` 替换成自身密钥服务取值或 AWS SDK v2 `RdsUtilities.generateAuthenticationToken(...)` 的调用，并设置调用超时、并发安全和必要缓存：

```java
// utilities 是宿主配置好 region 和 AWS credentials provider 的 RdsUtilities。
var raw = new TokenDataSource(jdbcUrl, dbUsername,
    () -> utilities.generateAuthenticationToken(request -> request
        .hostname(realRdsHostname).port(realRdsPort).username(dbUsername)));
var pool = new com.zaxxer.hikari.HikariDataSource();
pool.setDataSource(raw);
pool.setMaximumPoolSize(4);
pool.setMinimumIdle(0);
```

池复用已连接会话时不会重新取 token；新建物理连接时调用底层 DataSource。宿主自行添加 AWS SDK rds、认证依赖及 JDBC 驱动。使用真实 RDS hostname/port 签名，配置正确的数据库 IAM 权限、AWS 角色和 TLS 主机名验证。JDBC URL 不放用户名、密码或 token，以免覆盖 DataSource 提供的认证值。

这些样例说明宿主的实现位置，不代表本项目已经通过真实 AWS 联调。组件不计算端点身份摘要；同 job 恢复期间由宿主保证实际数据源、命名空间和迁移配置不变。若连接失败，先独立测试宿主 DataSource，再测试迁移；不要在错误日志中输出原始口令、token 或完整连接配置。
