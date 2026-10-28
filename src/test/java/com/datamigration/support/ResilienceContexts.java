package com.datamigration.support;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;

public final class ResilienceContexts {
    private ResilienceContexts() {}
    public static ConfigurableApplicationContext open(String location, Map<String, Object> overrides) {
        return new SpringApplicationBuilder(ResilienceHost.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("harness", overrides));
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
                        "--spring.main.banner-mode=off", "--data-migration.run-on-startup=false",
                        "--logging.level.root=OFF");
    }
}
