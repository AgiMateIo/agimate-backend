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

    /** Overrides the connector's disclosure axis for this one tool; {@code INHERIT} — the connector's value. */
    ToolDisclosure disclosure() default ToolDisclosure.INHERIT;

    /**
     * The worker's budget for awaiting the result, in seconds. {@code 0} — the worker's default
     * ({@code agent.tool.poll-timeout}, 60s). For long tools (media generation and the like) — up to
     * 30 minutes: anything larger is clamped by the worker. The budget bounds the wait only; the
     * execution on the backend is not cancelled.
     */
    int timeoutSeconds() default 0;

    /**
     * Who may call the tool. The default is the model only — the opposite of the MCP Apps default,
     * because one set of tools serves the agent, {@code /mcp} and views, and a silent widening costs
     * more than a declaration. {@code {}} — nobody: the method is only a dispatch target for
     * {@code executeJob} (dynamic {@code connector_jobs} rows, e.g. {@code time.fire}); declarative
     * background jobs use {@link Job}, which hides them on its own.
     */
    ToolVisibility[] visibility() default {ToolVisibility.MODEL};

    /**
     * The view this tool renders into — a full {@code ui://<connector code>/<name>} uri, as an external
     * server writes {@code _meta.ui.resourceUri}; empty — none. The connector must serve it
     * ({@code ClasspathViewProvider}); the tools the page itself calls need {@link ToolVisibility#VIEW}.
     */
    String view() default "";
}
