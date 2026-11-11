package com.datamigration.api;
import java.lang.annotation.*;
/** Marks a host DataSource. Zero selects automatic pool inspection; positive values optionally limit migration capacity. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface MigrationSource { int capacity() default 0; }
