package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One key-value pair for MCP {@code _meta}. Values are strings only — a limitation of Java
 * annotations (no {@code Map} or arbitrary JSON). Rich {@code _meta} belongs in the database or at
 * runtime.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ToolMeta {

    String key();

    String value();
}
