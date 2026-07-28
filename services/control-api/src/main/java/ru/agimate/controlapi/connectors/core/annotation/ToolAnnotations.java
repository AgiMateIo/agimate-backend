package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP {@code ToolAnnotations} — behavioural hints for the agent. They are hints (advisory): a client
 * is not obliged to trust them. The defaults are pessimistic, as in the MCP specification: assume the
 * tool writes, is destructive, is non-idempotent and reaches the outside world until told otherwise.
 *
 * <p>{@code @Target({})} — used only as a nested value inside {@link Tool}, never on its own.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ToolAnnotations {

    /** The tool changes no state → the agent may call it freely, in parallel and repeatedly. */
    boolean readOnlyHint() default false;

    /** May perform destructive changes (delete/overwrite). Meaningful only when {@code !readOnly}. */
    boolean destructiveHint() default true;

    /** A repeat call with the same arguments adds no further effect. */
    boolean idempotentHint() default false;

    /** Interacts with the outside world (network, external systems) vs a closed domain. */
    boolean openWorldHint() default true;
}
