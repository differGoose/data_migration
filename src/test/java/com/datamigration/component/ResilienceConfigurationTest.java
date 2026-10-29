package com.datamigration.component;

import com.datamigration.support.*;
import com.datamigration.properties.MigrationConfiguration;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ResilienceConfigurationTest {
    @TempDir Path directory;
    @Test void laterFilesOverrideScalarsAndReplaceWholeLists() throws Exception {
        Path first = directory.resolve("first.yml"), second = directory.resolve("second.yml");
        Files.writeString(first, """
                resilience:
                  environment: h2
                  trigger-commit: 1
                  configuration-classes: [com.datamigration.support.ResilienceHost]
                data-migration:
                  job-id: config-contract
                  tables:
                    - {name: old, target-table: OLD, select-sql: 'SELECT ID FROM OLD', insert-sql: 'INSERT INTO OLD VALUES (?)', fields: [{source-column: ID, target-jdbc-type: BIGINT}]}
                    - {name: other, target-table: OTHER, select-sql: 'SELECT ID FROM OTHER', insert-sql: 'INSERT INTO OTHER VALUES (?)', fields: [{source-column: ID, target-jdbc-type: BIGINT}]}
                """);
        Files.writeString(second, """
                resilience:
                  trigger-commit: 3
                  configuration-classes: []
                data-migration:
                  tables:
                    - {name: replacement, target-table: NEW_TABLE, select-sql: 'SELECT ID FROM NEW_TABLE', insert-sql: 'INSERT INTO NEW_TABLE VALUES (?)', fields: [{source-column: ID, target-jdbc-type: BIGINT}]}
                """);
        try (var context = ResilienceContexts.open(first.toUri() + "," + second.toUri(), Map.of())) {
            var settings = ResilienceSettings.read(context.getEnvironment());
            assertThat(settings.triggerCommit()).isEqualTo(3);
            assertThat(settings.configurationClasses()).isEmpty();
            assertThat(context.getBean(MigrationConfiguration.class).get().tables())
                    .extracting(t -> t.name()).containsExactly("replacement");
        }
    }
}
