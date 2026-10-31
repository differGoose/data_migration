package com.datamigration.e2e;

import static com.datamigration.support.MigrationAwait.await;

import com.datamigration.api.MigrationService;
import com.datamigration.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class H2MigrationIT {
    @TempDir Path directory;
    @Test void tenThousandRowsThroughYamlAndRealSpringServiceThenNewContextResume() throws Exception {
        try (var databases = new TcpDatabases(directory)) {
            databases.seed(10_000);
            try (var context = new SpringApplicationBuilder(ResilienceHost.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                var result = await(context.getBean(MigrationService.class));
                assertThat(result.tables().get(0).committedRows()).isEqualTo(10_000);
            }
            MigrationAssertions.assertEquivalent(databases);
            try (var context = new SpringApplicationBuilder(ResilienceHost.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                await(context.getBean(MigrationService.class));
            }
            MigrationAssertions.assertEquivalent(databases);
        }
    }

}
