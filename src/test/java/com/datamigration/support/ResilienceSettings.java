package com.datamigration.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/** Test harness settings, separate from the library's strict data-migration binding. */
public record ResilienceSettings(
        @DefaultValue("h2") String environment,
        @DefaultValue("SOURCE_OUTAGE") Scenario scenario,
        @DefaultValue("120s") Duration phaseTimeout,
        @DefaultValue("10000") int seedRows,
        @DefaultValue("customer") String triggerTask,
        @DefaultValue("1") int triggerCommit,
        @DefaultValue List<String> configurationClasses,
        @DefaultValue Proxy sourceProxy,
        @DefaultValue Proxy targetProxy,
        Map<String, Validation> validation) {
    public enum Scenario { SOURCE_OUTAGE, TARGET_OUTAGE, APP_BEFORE_COMMIT, APP_AFTER_COMMIT }
    public record Proxy(String upstreamHost, @DefaultValue("0") int upstreamPort,
                        @DefaultValue("0") int port) {}
    /** Queries return mapped columns in fields order and rows in the same unique-key order. */
    public record Validation(String sourceSql, String targetSql) {
        @Override public String toString() { return "Validation[SQL redacted]"; }
    }
    public static ResilienceSettings read(Environment environment) {
        var result = Binder.get(environment).bind("resilience", ResilienceSettings.class)
                .orElseThrow(() -> new IllegalArgumentException("Missing resilience configuration"));
        if (!List.of("h2", "dev").contains(result.environment()) || result.phaseTimeout() == null
                || result.triggerCommit() < 1 || result.triggerTask().isBlank()
                || result.phaseTimeout().isNegative() || result.phaseTimeout().isZero())
            throw new IllegalArgumentException("Invalid resilience environment or phase-timeout");
        return result;
    }
    @Override public String toString() { return "ResilienceSettings[redacted]"; }
}
