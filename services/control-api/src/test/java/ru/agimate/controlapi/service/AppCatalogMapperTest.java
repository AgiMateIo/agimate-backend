package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AppCatalogMapper — app catalog → connection_tools/triggers")
class AppCatalogMapperTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();

    private static JsonNode json(String raw) {
        try {
            return JsonUtils.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("tool")
    class ToolMapping {

        @Test
        @DisplayName("full descriptor stored losslessly (schema/annotations as raw JSON)")
        void fullDescriptor() {
            var descriptor = json("""
                    {
                      "title": "Speak text",
                      "description": "TTS",
                      "inputSchema": {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},
                      "outputSchema": {"type":"object","properties":{"spoken":{"type":"string"}}},
                      "annotations": {"readOnlyHint": false, "openWorldHint": false}
                    }
                    """);

            ConnectionTool tool = AppCatalogMapper.toolEntity(CONNECTION_ID, "tts_speak", descriptor);

            assertEquals("tts_speak", tool.getName());
            assertEquals("Speak text", tool.getTitle());
            assertEquals("TTS", tool.getDescription());
            assertEquals(json(tool.getInputSchema()), descriptor.get("inputSchema"));
            assertEquals(json(tool.getOutputSchema()), descriptor.get("outputSchema"));
            assertEquals(json(tool.getAnnotations()), descriptor.get("annotations"));
        }

        @Test
        @DisplayName("params shorthand synthesizes a minimal object schema")
        void paramsShorthand() {
            var descriptor = json("""
                    {"description": "Toggle a light", "params": ["lightId", "state"]}
                    """);

            ConnectionTool tool = AppCatalogMapper.toolEntity(CONNECTION_ID, "light_toggle", descriptor);

            JsonNode schema = json(tool.getInputSchema());
            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("properties").has("lightId"));
            assertTrue(schema.get("properties").has("state"));
            // типы неизвестны → пустая («any») схема свойства, required не проставляется
            assertTrue(schema.get("properties").get("lightId").isEmpty());
            assertNull(schema.get("required"));
        }

        @Test
        @DisplayName("inputSchema wins over params shorthand")
        void inputSchemaWins() {
            var descriptor = json("""
                    {
                      "inputSchema": {"type":"object","properties":{"text":{"type":"string"}}},
                      "params": ["ignored"]
                    }
                    """);

            ConnectionTool tool = AppCatalogMapper.toolEntity(CONNECTION_ID, "tts_speak", descriptor);

            assertEquals(descriptor.get("inputSchema"), json(tool.getInputSchema()));
        }

        @Test
        @DisplayName("bare tool (no schema, no params) → null schemas")
        void bareTool() {
            ConnectionTool tool = AppCatalogMapper.toolEntity(CONNECTION_ID, "ping", json("""
                    {"description": "ping"}
                    """));

            assertNull(tool.getInputSchema());
            assertNull(tool.getOutputSchema());
            assertNull(tool.getAnnotations());
        }
    }

    @Nested
    @DisplayName("trigger")
    class TriggerMapping {

        @Test
        @DisplayName("paramsSchema stored losslessly")
        void paramsSchema() {
            var descriptor = json("""
                    {
                      "description": "Door opened",
                      "paramsSchema": {"type":"object","properties":{"doorId":{"type":"string"}}}
                    }
                    """);

            ConnectionTrigger trigger = AppCatalogMapper.triggerEntity(CONNECTION_ID, "door_open", descriptor);

            assertEquals("door_open", trigger.getName());
            assertEquals("Door opened", trigger.getDescription());
            assertEquals(descriptor.get("paramsSchema"), json(trigger.getParamsSchema()));
        }

        @Test
        @DisplayName("params shorthand synthesizes paramsSchema")
        void paramsShorthand() {
            ConnectionTrigger trigger = AppCatalogMapper.triggerEntity(CONNECTION_ID, "door_open", json("""
                    {"params": ["doorId", "state"]}
                    """));

            JsonNode schema = json(trigger.getParamsSchema());
            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("properties").has("doorId"));
        }
    }
}
