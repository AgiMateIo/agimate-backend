package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;

import java.util.UUID;

/**
 * Каталог устройства (device link) → строки {@code connection_tools}/{@code connection_triggers}.
 * Формат дескриптора выровнен с MCP {@code tools/list[]} (ср. {@code McpToolMapper}): те же поля
 * {@code title}/{@code description}/{@code inputSchema}/{@code outputSchema}/{@code annotations}
 * сохраняются сырым JSON-текстом — лосслесс round-trip произвольной JSON Schema, ту же строку кэша
 * потом читает {@code ConnectionToolMapper.toSpec} для воркера/UI. Так app-тулы не «обделены»
 * относительно MCP и внутренних коннекторов.
 *
 * <p>Отличия от MCP: имя приходит ключом map (а не полем объекта), и поддержан shorthand
 * {@code params: string[]} — для простого устройства, которому неохота писать полную JSON Schema:
 * список имён разворачивается в минимальную {@code object}-схему (типы неизвестны → «any»).
 * {@code inputSchema} всегда приоритетнее {@code params}.
 */
@UtilityClass
public class AppCatalogMapper {

    /** Дескриптор тула (значение в map {@code tools}) → строка кэша. */
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

    /** Дескриптор триггера (значение в map {@code triggers}) → строка кэша. */
    public static ConnectionTrigger triggerEntity(UUID connectionId, String name, JsonNode descriptor) {
        return ConnectionTrigger.builder()
                .connectionId(connectionId)
                .name(name)
                .title(textOrNull(descriptor.get("title")))
                .description(textOrNull(descriptor.get("description")))
                .paramsSchema(schemaOrParams(descriptor.get("paramsSchema"), descriptor.get("params")))
                .build();
    }

    /** Явная схема как есть; иначе синтез из shorthand {@code params}; иначе {@code null}. */
    private static String schemaOrParams(JsonNode schema, JsonNode params) {
        if (schema != null && !schema.isNull()) {
            return schema.toString();
        }
        JsonNode synthesized = synthesizeObjectSchema(params);
        return synthesized != null ? synthesized.toString() : null;
    }

    /** {@code ["a","b"]} → {@code {"type":"object","properties":{"a":{},"b":{}}}} (типы неизвестны → «any»). */
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
