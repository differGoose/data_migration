package com.datamigration.properties;

import jakarta.validation.Validation;
import com.datamigration.diagnostics.MigrationLog;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.validation.FieldError;
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
            try (var diagnostic = MigrationLog.invocation()) {
                MigrationLog.stage("CONFIG_BIND", "-", "data-migration");
                MigrationLog.info("Migration configuration binding started", "");
                try {
                    try (var factory = Validation.buildDefaultValidatorFactory()) {
                        var handler = new ValidationBindHandler(
                            new NoUnboundElementsBindHandler(BindHandler.DEFAULT),
                            new SpringValidatorAdapter(factory.getValidator()));
                        properties = Binder.get(environment)
                            .bind("data-migration", Bindable.of(MigrationProperties.class), handler)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Missing data-migration configuration"));
                    }
                    MigrationLog.info("Migration configuration binding succeeded", "");
                } catch (RuntimeException failure) { reportBindingFailure(failure); throw failure; }
            }
        }
        return properties;
    }
    private void reportBindingFailure(RuntimeException failure) {
        try (var diagnostic = MigrationLog.invocation()) {
            MigrationLog.stage("CONFIG_BIND", "-", "data-migration");
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException binding)
                    MigrationLog.stage("CONFIG_BIND", "-", binding.getName().toString());
                if (cause instanceof UnboundConfigurationPropertiesException unbound) {
                    for (var property : unbound.getUnboundProperties()) {
                        MigrationLog.stage("CONFIG_BIND", "-", property.getName().toString());
                        MigrationLog.warn("Unknown migration configuration property", "reason=UNKNOWN_PROPERTY");
                    }
                }
                if (cause instanceof BindValidationException validation) {
                    for (var error : validation.getValidationErrors().getAllErrors()) {
                        String property = validation.getValidationErrors().getName().toString();
                        if (error instanceof FieldError field) property += "." + field.getField()
                                .replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
                        MigrationLog.stage("CONFIG_BIND", "-", property);
                        MigrationLog.warn("Invalid migration configuration property", "constraint=" + MigrationLog.safe(error.getCode()));
                    }
                }
            }
            MigrationLog.error("Migration configuration binding failed", failure);
        }
    }
}
