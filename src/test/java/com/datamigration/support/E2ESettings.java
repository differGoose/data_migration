package com.datamigration.support;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.core.env.Environment;

/** Test settings only: no database addresses, passwords, or pool creation. */
public record E2ESettings(
        @DefaultValue("MIGRATION") Scenario scenario,
        @DefaultValue("120s") Duration phaseTimeout,
        @DefaultValue("target/e2e-reports") String reportDirectory,
        @DefaultValue List<String> configurationClasses,
        Map<String, ResilienceSettings.Validation> validation,
        @DefaultValue("customer") String triggerTask,
        @DefaultValue("1") int triggerCommit,
        @DefaultValue ResilienceSettings.Proxy sourceProxy,
        @DefaultValue ResilienceSettings.Proxy targetProxy) {
    public enum Scenario { MIGRATION, SOURCE_OUTAGE, TARGET_OUTAGE, APP_BEFORE_COMMIT, APP_AFTER_COMMIT }
    public static E2ESettings read(Environment environment) {
        var settings=Binder.get(environment).bind("e2e",E2ESettings.class)
                .orElseThrow(() -> new IllegalArgumentException("Missing e2e configuration"));
        if (settings.phaseTimeout().isZero() || settings.phaseTimeout().isNegative()
                || settings.reportDirectory().isBlank() || settings.triggerCommit()<1)
            throw new IllegalArgumentException("Invalid E2E timeout, report directory or trigger commit");
        return settings;
    }
    /** In-memory adapter for the legacy fault controller. Never persisted to control/report files. */
    public Map<String,Object> resilienceOverrides() {
        if(scenario==Scenario.MIGRATION) throw new IllegalArgumentException("Choose a fault scenario");
        Map<String,Object> values=new HashMap<>();
        values.put("resilience.environment","dev"); values.put("resilience.scenario",scenario.name());
        values.put("resilience.phase-timeout",phaseTimeout.toString());
        values.put("resilience.trigger-task",triggerTask); values.put("resilience.trigger-commit",triggerCommit);
        for(int i=0;i<configurationClasses.size();i++) values.put("resilience.configuration-classes["+i+"]",configurationClasses.get(i));
        if(validation!=null) validation.forEach((name,query) -> {
            values.put("resilience.validation."+name+".source-sql",query.sourceSql());
            values.put("resilience.validation."+name+".target-sql",query.targetSql());
        });
        route(values,"source",sourceProxy); route(values,"target",targetProxy);
        return values;
    }
    private static void route(Map<String,Object> values,String side,ResilienceSettings.Proxy route) {
        if(route.upstreamHost()==null || route.upstreamPort()<1)
            throw new IllegalArgumentException("Configure external fault proxy routes");
        values.put("resilience."+side+"-proxy.upstream-host",route.upstreamHost());
        values.put("resilience."+side+"-proxy.upstream-port",route.upstreamPort());
        values.put("resilience."+side+"-proxy.port",route.port());
    }
    @Override public String toString() { return "E2ESettings[redacted]"; }
}
