package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        Integer timeoutSeconds
) {
}
