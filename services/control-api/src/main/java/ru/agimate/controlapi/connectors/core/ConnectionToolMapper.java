package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.ArrayList;
import java.util.List;

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
                null,
                null,
                parseUi(tool.getMeta()));
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

    /**
     * {@code _meta.ui} of MCP Apps. Servers written against the draft put the link under the flat key
     * {@code "ui/resourceUri"}; both shapes are live, the nested one wins when a server sends both.
     * A tool may declare visibility without a view of its own — a helper only its server's views call.
     * {@code null} when neither a {@code ui://} link nor a visibility is declared.
     */
    static ToolUi parseUi(String rawMeta) {
        JsonNode meta = rawMeta == null || rawMeta.isBlank() ? null : JsonUtils.toJsonNodeOrNull(rawMeta);
        if (meta == null || !meta.isObject()) {
            return null;
        }
        JsonNode ui = meta.path("ui");
        String resourceUri = textOrNull(ui.get("resourceUri"));
        if (resourceUri == null) {
            resourceUri = textOrNull(meta.get("ui/resourceUri"));
        }
        if (resourceUri != null && !resourceUri.startsWith("ui://")) {
            resourceUri = null;
        }
        List<String> visibility = null;
        JsonNode visibilityNode = ui.get("visibility");
        if (visibilityNode != null && visibilityNode.isArray()) {
            visibility = new ArrayList<>();
            for (JsonNode item : visibilityNode) {
                if (item.isTextual()) {
                    visibility.add(item.asText());
                }
            }
        }
        return resourceUri == null && visibility == null ? null : new ToolUi(resourceUri, visibility);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }
}
