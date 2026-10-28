package com.datamigration.e2e;

import com.datamigration.api.MigrationService;
import com.datamigration.autoconfigure.MigrationConnectionPools;
import com.datamigration.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class H2MigrationIT {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Host {}

    @Test void tenThousandRowsThroughYamlAndRealSpringServiceThenNewContextResume() throws Exception {
        try (var databases = new TcpDatabases(directory, false)) {
            databases.seed(10_000);
            try (var context = new SpringApplicationBuilder(Host.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                var result = context.getBean(MigrationService.class).migrate();
                assertThat(result.tables().getFirst().committedRows()).isEqualTo(10_000);
            }
            assertEquivalent(databases);
            try (var context = new SpringApplicationBuilder(Host.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml", "--data-migration.run-on-startup=true")) {
                assertThat(context.getBean(MigrationConnectionPools.class)).isNotNull();
            }
            assertEquivalent(databases);
        }
    }

    static void assertEquivalent(TcpDatabases databases) throws Exception {
        try (var source = DatabaseFixture.connection(databases.sourceUrl());
             var target = DatabaseFixture.connection(databases.targetUrl());
             var a = source.createStatement(); var b = target.createStatement();
             var left = a.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID");
             var right = b.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID")) {
            long count = 0;
            while (left.next()) {
                assertThat(right.next()).isTrue();
                assertThat(right.getLong(1)).isEqualTo(left.getLong(1));
                assertThat(right.getString(2)).isEqualTo(left.getString(2));
                assertThat(right.getBigDecimal(3)).isEqualByComparingTo(left.getBigDecimal(3));
                count++;
            }
            assertThat(right.next()).isFalse();
            assertThat(count).isEqualTo(10_000);
            try (var summary = b.executeQuery("SELECT committed_rows FROM migration_audit WHERE job_id='e2e' AND batch_no=0")) {
                assertThat(summary.next()).isTrue();
                assertThat(summary.getLong(1)).isEqualTo(10_000);
            }
        }
    }
}
