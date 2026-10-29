package com.datamigration.demo;

import com.datamigration.api.*;
import com.datamigration.support.TcpDatabases;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;

/** IDE runnable example: the HOST owns H2, Hikari and business application startup. */
public final class BeforeStartupDemo {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    public static class Application { }
    public static void main(String[] args) throws Exception {
        try(var databases=new TcpDatabases(Path.of("target","before-startup-"+System.nanoTime()),false)) {
            databases.seed(10_000);
            // Host-side factories; the library contains no pool or credentials configuration.
            try(var source=pool(databases.sourceUrl()); var target=pool(databases.targetUrl());
                var migration=MigrationRunner.builder().source(source,6).target(target,6)
                    .configLocations("classpath:e2e.yml")
                    .arguments("--data-migration.job-id=before-startup-demo").build()) {
                migration.migrate();
                migration.migrate(); // Same job: verify successful tasks without inserting again.
            }
            // A migration exception propagates before this line. No host beans existed above.
            try(var application=new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(args)) {
                System.out.println("Host started after successful migration");
            }
        }
    }
    private static HikariDataSource pool(String url) {
        var ds=new HikariDataSource(); ds.setJdbcUrl(url); ds.setUsername("sa"); ds.setPassword("");
        ds.setMaximumPoolSize(6); ds.setMinimumIdle(0); return ds;
    }
}
