package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpToolMapper.toEntity")
class McpToolMapperTest {

    private static final UUID IDENTITY = UUID.randomUUID();

    private static JsonNode tool(String json) {
        return JsonUtils.toJsonNode(json);
    }

    @Test
    @DisplayName("извлекает name/title/description и сохраняет сырые схемы текстом")
    void extractsFields() {
        ConnectionTool entity = McpToolMapper.toEntity(IDENTITY, tool("""
                {
                  "name": "search",
                  "title": "Search",
                  "description": "Search the web",
                  "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}}},
                  "annotations": {"readOnlyHint": true}
                }"""));

        assertEquals(IDENTITY, entity.getConnectionId());
        assertEquals("search", entity.getName());
        assertEquals("Search", entity.getTitle());
        assertEquals("Search the web", entity.getDescription());
        assertTrue(entity.getInputSchema().contains("\"q\""));
        assertTrue(entity.getAnnotations().contains("readOnlyHint"));
        assertNull(entity.getOutputSchema());
    }

    @Test
    @DisplayName("тул без имени отбрасывается (null)")
    void skipsNamelessTool() {
        assertNull(McpToolMapper.toEntity(IDENTITY, tool("{\"description\": \"x\"}")));
    }
}
