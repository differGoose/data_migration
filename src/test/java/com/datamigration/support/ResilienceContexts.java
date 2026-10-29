package com.datamigration.support;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;

public final class ResilienceContexts {
    private ResilienceContexts() {}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    public static class ExternalHost { }
    public static ConfigurableApplicationContext open(String location, Map<String, Object> overrides) {
        return new SpringApplicationBuilder("true".equals(String.valueOf(overrides.get("e2e.external-host")))
                ? ExternalHost.class : ResilienceHost.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("harness", overrides));
                    context.getEnvironment().getPropertySources().addLast(new MapPropertySource("unallocatedFaultPorts",Map.of(
                            "e2e.source-proxy.port",0,"e2e.target-proxy.port",0,
                            "resilience.source-proxy.port",0,"resilience.target-proxy.port",0)));
                    if(context.getEnvironment().containsProperty("e2e.scenario")) {
                        var e2e=E2ESettings.read(context.getEnvironment());
                        context.getEnvironment().getPropertySources().addAfter("harness",
                                new MapPropertySource("e2eFaultAdapter",e2e.resilienceOverrides()));
                    }
                    var settings = ResilienceSettings.read(context.getEnvironment());
                    for (String name : settings.configurationClasses()) {
                        try {
                            var reader = new org.springframework.context.annotation.AnnotatedBeanDefinitionReader(
                                    (org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory());
                            reader.register(Class.forName(name));
                        } catch (ClassNotFoundException e) { throw new IllegalArgumentException("Missing resilience configuration class"); }
                    }
                })
                .run("--spring.config.location=" + location, "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=OFF");
    }
}
