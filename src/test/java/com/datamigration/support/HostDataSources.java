package com.datamigration.support;

import com.datamigration.api.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

/** Test HOST owns credentials, pools and their shutdown. Not part of the migration library. */
@Configuration(proxyBeanMethods = false)
public class HostDataSources {
    @Bean(destroyMethod = "close") @Lazy @MigrationSource(capacity = 10)
    @ConditionalOnProperty("host-databases.source.jdbc-url")
    HikariDataSource hostSource(Environment env) { return create(env, "source"); }
    @Bean(destroyMethod = "close") @Lazy @MigrationTarget(capacity = 10)
    @ConditionalOnProperty("host-databases.target.jdbc-url")
    HikariDataSource hostTarget(Environment env) { return create(env, "target"); }
    private HikariDataSource create(Environment env, String side) {
        String prefix = "host-databases." + side + ".";
        var ds = new HikariDataSource();
        ds.setJdbcUrl(env.getRequiredProperty(prefix + "jdbc-url"));
        ds.setUsername(env.getProperty(prefix + "username", "sa"));
        ds.setPassword(env.getProperty(prefix + "password", ""));
        ds.setMaximumPoolSize(10); ds.setMinimumIdle(0);
        ds.setConnectionTimeout(env.getProperty(prefix + "connection-timeout", java.time.Duration.class,
                java.time.Duration.ofSeconds(2)).toMillis());
        return ds;
    }
}
