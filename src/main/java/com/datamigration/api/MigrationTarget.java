package com.datamigration.api;
import java.lang.annotation.*;
/** Marks the host DataSource and declares connections reserved for migration. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface MigrationTarget { int capacity(); }
