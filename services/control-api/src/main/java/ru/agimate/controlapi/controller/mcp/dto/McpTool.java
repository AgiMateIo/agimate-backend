package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;

import java.util.Map;

/**
 * A tool as {@code tools/list} shows it. Almost {@link ConnectorToolSpec}, which is already
 * MCP-shaped — but not it: the spec carries {@code timeoutSeconds}, an agreement with our own worker
 * that has no meaning for a client, and the name here is the namespaced one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpTool(
        String name,
        String title,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        ToolAnnotationsSpec annotations,
        @JsonProperty("_meta") Map<String, String> meta
) {

    public static McpTool of(String name, ConnectorToolSpec spec) {
        return new McpTool(name, spec.title(), spec.description(),
                spec.inputSchema(), spec.outputSchema(), spec.annotations(), spec.meta());
    }
}
