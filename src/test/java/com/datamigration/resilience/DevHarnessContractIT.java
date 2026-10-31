package com.datamigration.resilience;
import com.datamigration.support.*;

import com.datamigration.api.*;
import com.datamigration.support.TcpDatabases;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises the DEV path with manually prepared local servers; never claims Oracle/PG acceptance. */
class DevHarnessContractIT {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false)
    public static class ExternalDataSources {
        @Bean @Lazy @MigrationSource(capacity=6)
        javax.sql.DataSource externalSource(org.springframework.core.env.Environment env) {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource(env.getRequiredProperty("test.source-url"), "sa", "");
        }
        @Bean @Lazy @MigrationTarget(capacity=6)
        javax.sql.DataSource externalTarget(org.springframework.core.env.Environment env) {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource(env.getRequiredProperty("test.target-url"), "sa", "");
        }
    }
    @Test void externalDataSourcesSupportRepeatedCallsWithoutFixtureMutation() throws Exception {
        try (var databases = new TcpDatabases(directory.resolve("prepared"))) {
            databases.seed(10000); // Explicit preparation by the caller, outside the suite.
            Path config = directory.resolve("dev.properties");
            var properties = new Properties();
            databases.properties().forEach((k, v) -> properties.setProperty(k, v.toString()));
            properties.setProperty("spring.config.import", "classpath:resilience/h2.yml");
            properties.setProperty("resilience.environment", "dev");
            properties.setProperty("resilience.scenario", "TARGET_OUTAGE");
            properties.setProperty("resilience.configuration-classes[0]", ExternalDataSources.class.getName());
            for (String side : List.of("source", "target")) {
                String url = side.equals("source") ? databases.sourceUrl() : databases.targetUrl();
                int port = URI.create(url.substring("jdbc:h2:".length())).getPort();
                properties.setProperty("resilience." + side + "-proxy.upstream-host", "127.0.0.1");
                properties.setProperty("resilience." + side + "-proxy.upstream-port", "" + port);
                properties.setProperty("test." + side + "-url", url.replace(":" + port + "/",
                        ":${resilience." + side + "-proxy.port}/"));
                properties.remove("host-databases." + side + ".jdbc-url");
            }
            try (var writer = Files.newBufferedWriter(config)) { properties.store(writer, "synthetic local DEV contract"); }
            // Imported data has higher priority in Spring; explicit override selects DEV behavior.
            Map<String,Object> overrides = Map.of("resilience.environment", "dev", "resilience.scenario", "TARGET_OUTAGE");
            Path output = Path.of("target", "resilience-reports", "dev-contract-" + UUID.randomUUID());
            ResilienceSuite.run(config.toUri().toString(), overrides, output);
            try (var connection = com.datamigration.support.DatabaseFixture.connection(databases.targetUrl());
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(10000);
            }
            assertThat(Files.readString(output.resolve("result.properties"))).contains("environment=dev", "status=PASSED");
            // Same job on nonempty target uses the formal completed-job behavior, not a preflight rejection.
            Path repeat = directory.resolve("repeat");
            ResilienceSuite.run(config.toUri().toString(), overrides, repeat);
            assertThat(Files.readString(repeat.resolve("result.properties")))
                    .contains("status=PASSED", "faultTriggered=false", "alreadyCompleted=true");
        }
    }
}
