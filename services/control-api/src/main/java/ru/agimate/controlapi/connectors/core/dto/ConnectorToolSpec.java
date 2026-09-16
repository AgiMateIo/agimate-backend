package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.agimate.controlapi.database.enums.Disclosure;

import java.util.Map;

/**
 * MCP-compatible tool description returned by the connector SPI ({@code getTools()}). It replaces
 * langchain4j's {@code ToolSpecification}: besides name, description and the parameter schema it
 * carries the MCP fields title / {@link #annotations} / {@code _meta} / {@code outputSchema}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorToolSpec(
        String name,
        String title,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        ToolAnnotationsSpec annotations,
        @JsonProperty("_meta") Map<String, String> meta,
        /** The worker's budget for awaiting the result, in seconds; {@code null} — the worker's default. */
        Integer timeoutSeconds,
        /**
         * The tool's own disclosure axis ({@code @Tool(disclosure)}); {@code null} — the connector's
         * applies. The effective value is resolved at run-context assembly, not here.
         */
        Disclosure disclosure,
        /** The tool's view link and visibility; {@code null} — a foreign tool that declared neither. */
        ToolUi ui
) {

    /** A spec without a view. */
    public ConnectorToolSpec(String name, String title, String description, JsonSchema inputSchema,
                             JsonSchema outputSchema, ToolAnnotationsSpec annotations, Map<String, String> meta,
                             Integer timeoutSeconds, Disclosure disclosure) {
        this(name, title, description, inputSchema, outputSchema, annotations, meta, timeoutSeconds, disclosure, null);
    }

    /** A spec without a per-tool override — the connector's axis applies. */
    public ConnectorToolSpec(String name, String title, String description, JsonSchema inputSchema,
                             JsonSchema outputSchema, ToolAnnotationsSpec annotations, Map<String, String> meta,
                             Integer timeoutSeconds) {
        this(name, title, description, inputSchema, outputSchema, annotations, meta, timeoutSeconds, null, null);
    }
}
