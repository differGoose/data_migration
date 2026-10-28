package com.datamigration.autoconfigure;

import com.datamigration.api.MigrationResult;
import com.datamigration.api.MigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class MigrationStartupTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Host {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CustomHost {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean MigrationService customMigrationService(AtomicInteger calls) {
            return () -> {
                calls.incrementAndGet();
                return new MigrationResult(UUID.randomUUID(), Instant.now(), Instant.now(), List.of());
            };
        }
    }

    @Test void automaticDiscoveryAlwaysExportsMethodWithoutConfiguration() {
        try (var context = new SpringApplicationBuilder(Host.class)
                .web(WebApplicationType.NONE).run("--spring.main.banner-mode=off")) {
            assertThat(context.getBean(MigrationService.class)).isNotNull();
            assertThat(context.getBeanFactory().containsSingleton("migrationConnectionPools")).isFalse();
        }
    }

    @Test void startupOffStillAllowsManualCall() {
        try (var context = new SpringApplicationBuilder(CustomHost.class)
                .web(WebApplicationType.NONE).run("--data-migration.run-on-startup=false")) {
            assertThat(context.getBean(AtomicInteger.class)).hasValue(0);
            context.getBean(MigrationService.class).migrate();
            assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
        }
    }

    @Test void startupOnAutomaticallyInvokesTheSameServiceOnce() {
        try (var context = new SpringApplicationBuilder(CustomHost.class)
                .web(WebApplicationType.NONE).run("--data-migration.run-on-startup=true")) {
            assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
            context.getBean(MigrationService.class).migrate();
            assertThat(context.getBean(AtomicInteger.class)).hasValue(2);
        }
    }
}
