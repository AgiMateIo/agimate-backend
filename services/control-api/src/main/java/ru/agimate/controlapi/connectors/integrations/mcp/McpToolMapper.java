package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

/**
 * MCP-специфичный маппинг discovery: сырой JSON из {@code tools/list} → строка кэша
 * {@link ConnectionTool} (схемы сохраняются сырым текстом для фиделити). Обратное направление
 * (строка кэша → {@code ConnectorToolSpec}) — общий {@code ConnectionToolMapper} в {@code core}.
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

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String rawOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.toString() : null;
    }
}
