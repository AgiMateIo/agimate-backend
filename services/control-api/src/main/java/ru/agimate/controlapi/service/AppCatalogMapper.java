package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;

import java.util.UUID;

/**
 * A device's catalogue (device link) → rows in {@code connection_tools}/{@code connection_triggers}.
 * The descriptor's format is aligned with MCP {@code tools/list[]} (cf. {@code McpToolMapper}): the same
 * fields {@code title}/{@code description}/{@code inputSchema}/{@code outputSchema}/{@code annotations}
 * are kept as raw JSON text — a lossless round-trip of an arbitrary JSON Schema, and the same cache row
 * is later read by {@code ConnectionToolMapper.toSpec} for the worker and the UI. That way app tools are
 * not «short-changed» relative to MCP and the internal connectors.
 *
 * <p>The differences from MCP: the name arrives as the map's key (rather than a field of the object),
 * and the shorthand {@code params: string[]} is supported — for a simple device that would rather not
 * write a full JSON Schema: the list of names expands into a minimal {@code object} schema (types are
 * unknown → «any»). {@code inputSchema} always takes precedence over {@code params}.
 */
@UtilityClass
public class AppCatalogMapper {

    /** A tool's descriptor (the value in the {@code tools} map) → a cache row. */
    public static ConnectionTool toolEntity(UUID connectionId, String name, JsonNode descriptor) {
        return ConnectionTool.builder()
                .connectionId(connectionId)
                .name(name)
                .title(textOrNull(descriptor.get("title")))
                .description(textOrNull(descriptor.get("description")))
                .inputSchema(schemaOrParams(descriptor.get("inputSchema"), descriptor.get("params")))
                .outputSchema(rawOrNull(descriptor.get("outputSchema")))
                .annotations(rawOrNull(descriptor.get("annotations")))
                .build();
    }

    /** A trigger's descriptor (the value in the {@code triggers} map) → a cache row. */
    public static ConnectionTrigger triggerEntity(UUID connectionId, String name, JsonNode descriptor) {
        return ConnectionTrigger.builder()
                .connectionId(connectionId)
                .name(name)
                .title(textOrNull(descriptor.get("title")))
                .description(textOrNull(descriptor.get("description")))
                .paramsSchema(schemaOrParams(descriptor.get("paramsSchema"), descriptor.get("params")))
                .build();
    }

    /** The explicit schema as-is; otherwise synthesised from the shorthand {@code params}; otherwise {@code null}. */
    private static String schemaOrParams(JsonNode schema, JsonNode params) {
        if (schema != null && !schema.isNull()) {
            return schema.toString();
        }
        JsonNode synthesized = synthesizeObjectSchema(params);
        return synthesized != null ? synthesized.toString() : null;
    }

    /** {@code ["a","b"]} → {@code {"type":"object","properties":{"a":{},"b":{}}}} (types unknown → «any»). */
    private static JsonNode synthesizeObjectSchema(JsonNode params) {
        if (params == null || !params.isArray() || params.isEmpty()) {
            return null;
        }
        ObjectNode schema = JsonUtils.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        params.forEach(p -> {
            if (p != null && p.isTextual() && !p.asText().isBlank()) {
                properties.putObject(p.asText());
            }
        });
        return properties.isEmpty() ? null : schema;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String rawOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.toString() : null;
    }
}
