package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpToolMapper")
class McpToolMapperTest {

    private static final UUID IDENTITY = UUID.randomUUID();

    private static JsonNode tool(String json) {
        return JsonUtils.toJsonNode(json);
    }

    @Nested
    @DisplayName("toEntity")
    class ToEntity {

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

    @Nested
    @DisplayName("toSpec")
    class ToSpec {

        @Test
        @DisplayName("фиделити: нестандартные ключевые слова JSON Schema (anyOf/format/default) переживают round-trip")
        void preservesArbitrarySchemaKeywords() {
            String rawSchema = """
                    {"type":"object","properties":{
                       "when":{"type":"string","format":"date-time","default":"now"},
                       "mode":{"anyOf":[{"type":"string"},{"type":"integer"}]}
                    },"required":["when"]}""";
            ConnectionTool entity = McpToolMapper.toEntity(IDENTITY, tool(
                    "{\"name\":\"t\",\"inputSchema\":" + rawSchema + "}"));

            ConnectorToolSpec spec = McpToolMapper.toSpec(entity);
            String reserialized = JsonUtils.writeValueAsString(spec.inputSchema());

            assertTrue(reserialized.contains("anyOf"), "anyOf must survive");
            assertTrue(reserialized.contains("format"), "format must survive");
            assertTrue(reserialized.contains("date-time"), "format value must survive");
            assertTrue(reserialized.contains("default"), "default must survive");
        }

        @Test
        @DisplayName("annotations парсятся в ToolAnnotationsSpec; пустые схемы → null")
        void parsesAnnotations() {
            ConnectionTool entity = McpToolMapper.toEntity(IDENTITY, tool("""
                    {"name":"t","annotations":{"readOnlyHint":true,"destructiveHint":false,
                     "idempotentHint":true,"openWorldHint":false}}"""));

            ConnectorToolSpec spec = McpToolMapper.toSpec(entity);

            assertTrue(spec.annotations().readOnlyHint());
            assertTrue(spec.annotations().idempotentHint());
            assertNull(spec.inputSchema());
        }
    }
}
