package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.model.AgentChatMessage;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.ConnectorToolSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static ConnectorToolSpec spec(String name, String namespace, String connectionId, String schema) {
        ConnectorToolSpec.Builder b = ConnectorToolSpec.newBuilder()
                .setName(name)
                .setNamespace(namespace)
                .setConnectionId(connectionId)
                .setDescription("desc-" + name);
        if (schema != null) {
            b.setInputSchema(ByteString.copyFrom(schema, StandardCharsets.UTF_8));
        }
        return b.build();
    }

    @Nested
    @DisplayName("sanitizeToolName")
    class Sanitize {
        @Test
        @DisplayName("maps dots to __ and other unsafe chars to _")
        void sanitizes() {
            assertEquals("board__get_tasks", ToolRegistry.sanitizeToolName("board.get_tasks"));
            assertEquals("a_b_c", ToolRegistry.sanitizeToolName("a b/c"));
        }
    }

    @Nested
    @DisplayName("parseToolSchema")
    class ParseSchema {
        @Test
        @DisplayName("falls back to empty-object schema for blank or bare object schemas")
        void fallback() {
            assertTrue(ToolRegistry.parseToolSchema(spec("t", "ns", "c", "")).contains("additionalProperties"));
            assertTrue(ToolRegistry.parseToolSchema(spec("t", "ns", "c", "{\"type\":\"object\"}"))
                    .contains("\"properties\":{}"));
        }

        @Test
        @DisplayName("passes a real schema through unchanged")
        void passthrough() {
            String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}";
            assertEquals(schema, ToolRegistry.parseToolSchema(spec("t", "ns", "c", schema)));
        }
    }

    @Nested
    @DisplayName("build / resolve")
    class BuildResolve {
        @Test
        @DisplayName("namespaces LLM names and resolves back to backend routing")
        void resolves() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    new ToolRegistry.ConnectorTools("board", List.of(spec("get_tasks", "board", "conn-1", null))),
                    new ToolRegistry.ConnectorTools("mcp", List.of(spec("search", "mcp_ctx7", "conn-2", null)))));

            assertEquals(List.of("board__get_tasks", "mcp_ctx7__search"), reg.names());

            ToolRegistry.BackendTool bt = reg.resolve("mcp_ctx7__search");
            assertEquals("mcp", bt.connectorCode());
            assertEquals("search", bt.name());
            assertEquals("conn-2", bt.identity());
            assertNull(reg.resolve("unknown_tool"));
        }

        @Test
        @DisplayName("display names project an assistant's tool calls back to backend names")
        void displayNames() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    new ToolRegistry.ConnectorTools("board", List.of(spec("get_tasks", "board", "c", null)))));
            AgentChatMessage assistant = AgentChatMessage.assistant(null, false,
                    List.of(new AgentChatMessage.ToolCall("id1", "board__get_tasks", "{}")));
            assertEquals(List.of("get_tasks"), reg.displayNames(assistant));
        }
    }
}
