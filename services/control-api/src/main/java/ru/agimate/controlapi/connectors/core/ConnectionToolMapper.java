package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.database.entities.ConnectionTool;

/**
 * Общий маппинг динамических тулов: строка кэша {@link ConnectionTool} (сырые JSON-схемы текстом) →
 * MCP-совместимый {@link ConnectorToolSpec}. Механизм не привязан к MCP — им пользуется листинг
 * доступных агенту тулов ({@code ToolDefinitionService}/{@code RunContextService}) и сами
 * динамические коннекторы. Схемы парсятся в {@link JsonSchema} лишь при отдаче спека —
 * {@code @JsonAnySetter} в {@link JsonSchema} гарантирует лосслесс round-trip произвольной JSON Schema.
 */
@UtilityClass
public class ConnectionToolMapper {

    /** Строка кэша → MCP-совместимый спек для воркера/UI. */
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
     * Сырой JSON тула (напр. элемент MCP {@code tools/list[]}) → спек с заданным (неймспейс-)именем,
     * без похода в кэш. Для session-scoped тулов, проброшенных из клиента (не персистятся в
     * {@code connection_tools}).
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
