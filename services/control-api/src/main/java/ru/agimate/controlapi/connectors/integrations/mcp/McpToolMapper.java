package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

/**
 * MCP-specific mapping of discovery: raw JSON from {@code tools/list} → a cache row
 * {@link ConnectionTool} (schemas are kept as raw text for fidelity). The reverse direction (a cache
 * row → a {@code ConnectorToolSpec}) is the shared {@code ConnectionToolMapper} in {@code core}.
 */
@UtilityClass
public class McpToolMapper {

    /** Raw tool JSON ({@code tools/list[]}) → a cache row. {@code null} when there is no name. */
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
