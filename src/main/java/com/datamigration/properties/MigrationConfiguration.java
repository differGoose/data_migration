package com.datamigration.properties;

import jakarta.validation.Validation;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.core.env.Environment;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * Binds and validates once on first use, not on host application startup.
 * No live reload: subsequent invocations reuse the validated configuration.
 */
public final class MigrationConfiguration {
    private final Environment environment;
    private MigrationProperties properties;

    public MigrationConfiguration(Environment environment) {
        this.environment = environment;
    }

    public synchronized MigrationProperties get() {
        if (properties == null) {
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                var handler = new ValidationBindHandler(
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT),
                        new SpringValidatorAdapter(factory.getValidator()));
                properties = Binder.get(environment)
                        .bind("data-migration", Bindable.of(MigrationProperties.class), handler)
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing data-migration configuration; configure source, target and tables before calling migrate()."));
            }
        }
        return properties;
    }
}
