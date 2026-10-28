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
    public static class Credentials {
        @Bean DatabaseCredentialsProvider harnessCredentials() {
            return (role, endpoint) -> new DatabaseCredentials(endpoint.username(), "");
        }
    }
    @Test void externalConfigurationAndCredentialBeansWorkWithoutDevFixtureMutation() throws Exception {
        try (var databases = new TcpDatabases(directory.resolve("prepared"), false)) {
            databases.seed(10000); // Explicit preparation by the caller, outside the suite.
            Path config = directory.resolve("dev.properties");
            var properties = new Properties();
            databases.properties().forEach((k, v) -> properties.setProperty(k, v.toString()));
            properties.setProperty("spring.config.import", "classpath:resilience/h2.yml");
            properties.setProperty("resilience.environment", "dev");
            properties.setProperty("resilience.scenario", "TARGET_OUTAGE");
            properties.setProperty("resilience.configuration-classes[0]", Credentials.class.getName());
            for (String side : List.of("source", "target")) {
                String url = side.equals("source") ? databases.sourceUrl() : databases.targetUrl();
                int port = URI.create(url.substring("jdbc:h2:".length())).getPort();
                properties.setProperty("resilience." + side + "-proxy.upstream-host", "127.0.0.1");
                properties.setProperty("resilience." + side + "-proxy.upstream-port", "" + port);
                properties.setProperty("data-migration." + side + ".jdbc-url", url.replace(":" + port + "/",
                        ":${resilience." + side + "-proxy.port}/"));
                properties.remove("data-migration." + side + ".password");
                properties.setProperty("data-migration." + side + ".credentials-provider", "harnessCredentials");
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
            // New scenario on a nonempty target must fail before writing/clearing anything.
            assertThatThrownBy(() -> ResilienceSuite.run(config.toUri().toString(), overrides, directory.resolve("reject")))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("must be empty");
        }
    }
}
