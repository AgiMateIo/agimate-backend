package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP-compatible description of a connector's tool.
 * {@code name}/{@code title}/{@code description}/{@link ToolAnnotations}/{@code _meta} are declared
 * statically on the method; {@code inputSchema} and {@code outputSchema} are built by reflection
 * ({@link ru.agimate.controlapi.connectors.core.ToolSchemaReflector}) from the method's signature
 * (parameters carrying {@link ToolParam})
 * and its return type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** Unique tool name (the dispatch key). */
    String name();

    /** Human-readable name for the UI; empty by default → falls back to {@code name}. */
    String title() default "";

    String description() default "";

    /** Behavioural hints for the agent (MCP {@code annotations}). */
    ToolAnnotations annotations() default @ToolAnnotations;

    /** Arbitrary string metadata (MCP {@code _meta}). */
    ToolMeta[] meta() default {};

    /**
     * The worker's budget for awaiting the result, in seconds. {@code 0} — the worker's default
     * ({@code agent.tool.poll-timeout}, 60s). For long tools (media generation and the like) — up to
     * 30 minutes: anything larger is clamped by the worker. The budget bounds the wait only; the
     * execution on the backend is not cancelled.
     */
    int timeoutSeconds() default 0;

    /**
     * {@code true} — the method is hidden from the LLM (absent from {@code getTools()}, unreachable
     * through {@code executeTool}) but remains a dispatch target for {@code executeJob} (dynamic
     * {@code connector_jobs} rows, e.g. {@code time.fire}). For declarative background jobs use
     * {@link Job} — those are hidden on their own.
     */
    boolean internal() default false;
}
