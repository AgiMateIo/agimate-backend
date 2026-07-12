package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

/**
 * Маппинг тула MCP: сырой JSON из {@code tools/list} ↔ строка {@link ConnectionTool} ↔
 * {@link ConnectorToolSpec}. Схемы хранятся сырым текстом (фиделити), парсятся в {@link JsonSchema}
 * лишь при отдаче спека — {@code @JsonAnySetter} в {@link JsonSchema} гарантирует лосслесс round-trip.
 */
@UtilityClass
public class McpToolMapper {

    /** Сырой JSON тула ({@code tools/list[]}) → строка кэша. {@code null}, если нет имени. */
    public static ConnectionTool toEntity(UUID connectionId, JsonNode tool) {
        String name = textOrNull(tool.get("name"));
        if (name == null || name.isBlank()) {
            return null;
        }
        return ConnectionTool.builder()
                .connectionId(connectionId)
                .name(name)
                .title(textOrNull(tool.get("title")))
                .description(textOrNull(tool.get("description")))
                .inputSchema(rawOrNull(tool.get("inputSchema")))
                .outputSchema(rawOrNull(tool.get("outputSchema")))
                .annotations(rawOrNull(tool.get("annotations")))
                .build();
    }

    /**
     * Сырой JSON тула ({@code tools/list[]}) → спек с заданным (неймспейс-)именем, без похода в кэш.
     * Для session-scoped MCP-тулов, проброшенных из IDE (не персистятся в {@code connection_tools}).
     */
    public static ConnectorToolSpec toSpec(String name, JsonNode tool) {
        return new ConnectorToolSpec(
                name,
                textOrNull(tool.get("title")),
                textOrNull(tool.get("description")),
                parseSchemaNode(tool.get("inputSchema")),
                parseSchemaNode(tool.get("outputSchema")),
                parseAnnotationsNode(tool.get("annotations")),
                null);
    }

    /** Строка кэша → MCP-совместимый спек для воркера/UI. */
    public static ConnectorToolSpec toSpec(ConnectionTool tool) {
        return new ConnectorToolSpec(
                tool.getName(),
                tool.getTitle(),
                tool.getDescription(),
                parseSchema(tool.getInputSchema()),
                parseSchema(tool.getOutputSchema()),
                parseAnnotations(tool.getAnnotations()),
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

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String rawOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.toString() : null;
    }
}
