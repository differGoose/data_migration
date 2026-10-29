package com.datamigration.e2e;

import com.datamigration.api.MigrationService;
import com.datamigration.jdbc.DataSourceConnections;
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
    @Test void tenThousandRowsThroughYamlAndRealSpringServiceThenNewContextResume() throws Exception {
        try (var databases = new TcpDatabases(directory, false)) {
            databases.seed(10_000);
            try (var context = new SpringApplicationBuilder(ResilienceHost.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                var result = context.getBean(MigrationService.class).migrate();
                assertThat(result.tables().get(0).committedRows()).isEqualTo(10_000);
            }
            MigrationAssertions.assertEquivalent(databases);
            try (var context = new SpringApplicationBuilder(ResilienceHost.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                context.getBean(MigrationService.class).migrate();
            }
            MigrationAssertions.assertEquivalent(databases);
        }
    }

}
