package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.database.entities.ConnectionTool;

/**
 * Shared mapping of dynamic tools: a cache row {@link ConnectionTool} (raw JSON schemas as text) →
 * an MCP-compatible {@link ConnectorToolSpec}. The mechanism is not tied to MCP — it is used by the
 * listing of tools available to an agent ({@code ToolDefinitionService}/{@code RunContextService})
 * and by the dynamic connectors themselves. Schemas are parsed into {@link JsonSchema} only when a
 * spec is handed out — {@code @JsonAnySetter} in {@link JsonSchema} guarantees a lossless round-trip
 * of an arbitrary JSON Schema.
 */
@UtilityClass
public class ConnectionToolMapper {

    /** Cache row → an MCP-compatible spec for the worker and the UI. */
    public static ConnectorToolSpec toSpec(ConnectionTool tool) {
        return new ConnectorToolSpec(
                tool.getName(),
                tool.getTitle(),
                tool.getDescription(),
                parseSchema(tool.getInputSchema()),
                parseSchema(tool.getOutputSchema()),
                parseAnnotations(tool.getAnnotations()),
                null,
                null);
    }

    /**
     * Raw tool JSON (e.g. an element of MCP {@code tools/list[]}) → a spec under the given
     * (namespaced) name, without consulting the cache. For session-scoped tools passed through from
     * the client (they are not persisted into {@code connection_tools}).
     */
    public static ConnectorToolSpec toSpec(String name, JsonNode tool) {
        return new ConnectorToolSpec(
                name,
                textOrNull(tool.get("title")),
                textOrNull(tool.get("description")),
                parseSchemaNode(tool.get("inputSchema")),
                parseSchemaNode(tool.get("outputSchema")),
                parseAnnotationsNode(tool.get("annotations")),
                null,
                null);
    }

    private static JsonSchema parseSchema(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.MAPPER.readValue(raw, JsonSchema.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonSchema parseSchemaNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return JsonUtils.MAPPER.convertValue(node, JsonSchema.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolAnnotationsSpec parseAnnotations(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.MAPPER.readValue(raw, ToolAnnotationsSpec.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolAnnotationsSpec parseAnnotationsNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return JsonUtils.MAPPER.convertValue(node, ToolAnnotationsSpec.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }
}
