package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConnectionToolMapper.toSpec")
class ConnectionToolMapperTest {

    private static ConnectionTool tool(String inputSchema, String annotations) {
        return ConnectionTool.builder()
                .connectionId(UUID.randomUUID())
                .name("t")
                .inputSchema(inputSchema)
                .annotations(annotations)
                .build();
    }

    @Test
    @DisplayName("фиделити: нестандартные ключевые слова JSON Schema (anyOf/format/default) переживают round-trip")
    void preservesArbitrarySchemaKeywords() {
        String rawSchema = """
                {"type":"object","properties":{
                   "when":{"type":"string","format":"date-time","default":"now"},
                   "mode":{"anyOf":[{"type":"string"},{"type":"integer"}]}
                },"required":["when"]}""";

        ConnectorToolSpec spec = ConnectionToolMapper.toSpec(tool(rawSchema, null));
        String reserialized = JsonUtils.writeValueAsString(spec.inputSchema());

        assertTrue(reserialized.contains("anyOf"), "anyOf must survive");
        assertTrue(reserialized.contains("format"), "format must survive");
        assertTrue(reserialized.contains("date-time"), "format value must survive");
        assertTrue(reserialized.contains("default"), "default must survive");
    }

    @Test
    @DisplayName("annotations парсятся в ToolAnnotationsSpec; пустые схемы → null")
    void parsesAnnotations() {
        ConnectorToolSpec spec = ConnectionToolMapper.toSpec(tool(null,
                """
                {"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}"""));

        assertTrue(spec.annotations().readOnlyHint());
        assertTrue(spec.annotations().idempotentHint());
        assertNull(spec.inputSchema());
    }

    @Test
    @DisplayName("сырой JSON + заданное имя (session-scoped тул из IDE) → спек с этим именем и фиделити схемы")
    void mapsRawNodeWithExplicitName() {
        JsonNode node = JsonUtils.toJsonNode("""
                {
                  "name": "search",
                  "title": "Search",
                  "description": "Search the web",
                  "inputSchema": {"type":"object","properties":{
                     "when":{"type":"string","format":"date-time"}}},
                  "annotations": {"readOnlyHint": true}
                }""");

        ConnectorToolSpec spec = ConnectionToolMapper.toSpec("srv__search", node);

        assertEquals("srv__search", spec.name(), "имя берётся из аргумента, а не из node.name");
        assertEquals("Search", spec.title());
        assertEquals("Search the web", spec.description());
        assertTrue(spec.annotations().readOnlyHint());
        assertTrue(JsonUtils.writeValueAsString(spec.inputSchema()).contains("date-time"),
                "нестандартный keyword схемы переживает round-trip");
    }
}
