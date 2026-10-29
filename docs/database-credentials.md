# How the host supplies DataSources, password decryption, and IAM

**The component receives DataSources that can already connect; it no longer reads database passwords itself.** The host owns decryption, secret services, IAM, pools, and timeouts. If you already have usable DataSources, pass them to MigrationRunner or register them through source/target annotations.

## Ordinary passwords

Create pools in the host factory (add Hikari to the host yourself; Oracle/PostgreSQL drivers come from the component's runtime dependencies):

```java
var source = new com.zaxxer.hikari.HikariDataSource();
source.setJdbcUrl(System.getenv("SOURCE_JDBC_URL"));
source.setUsername(System.getenv("SOURCE_USERNAME"));
source.setPassword(System.getenv("SOURCE_PASSWORD"));
source.setMaximumPoolSize(4);
source.setMinimumIdle(0);
source.setConnectionTimeout(10000);
```

Pass it to the Runner using `.source(source, 4)`. The host still owns the pool; the Runner does not close it. Do the same separately for source and target.

## Custom decryption: where ciphertext becomes plaintext

Suppose the host environment variable SOURCE_ENCRYPTED_PASSWORD contains ciphertext and your project has a `ProjectSecretService`:

```java
// Define this method in the host factory; replace ProjectSecretService with your actual type.
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

The call order is: **host obtains ciphertext -> host decrypts it -> host creates a DataSource -> migration borrows connections**. The component supplies no decryption algorithm, and no component credential interface needs to be implemented.

ProjectSecretService is a placeholder, not a built-in class. Replace it with your existing utility/service. The algorithm and key must match those used for encryption. If decryption is unavailable, first identify the ciphertext source and key-management method. Base64 decoding is not decryption. Do not commit ciphertext together with its key or print plaintext.

Before main calls `.run()`, ordinary Spring beans do not exist, so this service cannot be autowired. The host should create decryption capability through an ordinary Java factory/SDK client. If your service requires the full host context, extract the minimum decryption capability into an independently initialized factory before using pre-startup migration.

For migration inside a context, inject the project's decryption service into a host @Bean method and create a DataSource marked `@MigrationSource(capacity=4)`. See [host integration](getting-started.md).

## Secret services and IAM: obtain credentials for new physical connections

Decrypting a static password into a pool works when it remains valid for the job. IAM tokens expire; generating one only at application startup and treating it as a permanent pool password is insufficient.

Have the host supply an underlying DataSource that obtains valid credentials on each getConnection, then wrap it with the host's pool. The following is a host implementation example. `AbstractDataSource` comes from spring-jdbc added by the host, not a component extension interface:

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

Replace `token` with your secret-service lookup or AWS SDK v2 `RdsUtilities.generateAuthenticationToken(...)` call. Configure timeouts, thread safety, and any required caching:

```java
// utilities is the host's RdsUtilities with region and AWS credentials provider configured.
var raw = new TokenDataSource(jdbcUrl, dbUsername,
    () -> utilities.generateAuthenticationToken(request -> request
        .hostname(realRdsHostname).port(realRdsPort).username(dbUsername)));
var pool = new com.zaxxer.hikari.HikariDataSource();
pool.setDataSource(raw);
pool.setMaximumPoolSize(4);
pool.setMinimumIdle(0);
```

Reusing a pooled connected session does not obtain another token; creating a physical connection invokes the underlying DataSource. The host adds AWS SDK rds and authentication dependencies; Oracle/PostgreSQL JDBC drivers are already supplied. Sign with the real RDS hostname/port and configure database IAM permissions, AWS roles, and TLS hostname verification. Do not put usernames, passwords, or tokens in JDBC URLs, which could override credentials supplied by the DataSource.

These examples identify where host code belongs; they do not establish real AWS validation. The component computes no endpoint identity digest. During recovery the host preserves actual DataSources, namespaces, and migration rules. If connections fail, test the host DataSource independently before migration. Do not log raw passwords, tokens, or complete connection configuration.
