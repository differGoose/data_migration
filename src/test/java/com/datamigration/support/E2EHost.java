package com.datamigration.support;

import com.datamigration.api.*;
import java.lang.annotation.Annotation;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.*;

/** Loads caller-owned configuration classes; no H2 host or library autoconfiguration is imported. */
public final class E2EHost {
    private E2EHost() { }
    public static ConfigurableEnvironment environment(String... locations) {
        if (locations.length==0) throw new IllegalArgumentException("Provide E2E configuration locations");
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("e2eLocations",
                Map.of("spring.config.location",String.join(",",locations))));
        ConfigDataEnvironmentPostProcessor.applyTo(env);
        return env;
    }
    public static AnnotationConfigApplicationContext open(ConfigurableEnvironment env, List<String> classes) {
        if (classes.isEmpty()) throw new IllegalArgumentException("Provide external DataSource configuration classes; no H2 fallback");
        var context=new AnnotationConfigApplicationContext(); context.setEnvironment(env);
        try {
            for (String name:classes) context.register(Class.forName(name));
            context.refresh(); return context;
        } catch (Throwable failure) {
            context.close();
            throw new IllegalArgumentException("Cannot load external E2E DataSource configuration");
        }
    }
    public record Endpoint(DataSource dataSource,int capacity) { }
    public static Endpoint endpoint(org.springframework.context.ConfigurableApplicationContext context,Class<? extends Annotation> role) {
        var beans=context.getBeanFactory(); var names=new ArrayList<String>();
        for (String name:beans.getBeanNamesForType(DataSource.class,true,false))
            if (beans.findAnnotationOnBean(name,role)!=null) names.add(name);
        if (names.size()!=1) throw new IllegalArgumentException("Require exactly one external @"+role.getSimpleName()+" DataSource");
        String name=names.get(0);
        int capacity=role==MigrationSource.class?beans.findAnnotationOnBean(name,MigrationSource.class).capacity()
                :beans.findAnnotationOnBean(name,MigrationTarget.class).capacity();
        if (capacity<1) throw new IllegalArgumentException("External E2E capacity must be positive");
        return new Endpoint(beans.getBean(name,DataSource.class),capacity);
    }
}
