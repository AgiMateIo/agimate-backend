package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.model.AgentChatMessage;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.Disclosure;
import ru.agimate.agentworker.ToolAnnotations;
import ru.agimate.agentworker.agent.model.ContextMaterial;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static ConnectorToolSpec spec(String name, String connectorCode, String namespace,
                                          String connectionId, String schema) {
        ConnectorToolSpec.Builder b = ConnectorToolSpec.newBuilder()
                .setName(name)
                .setConnectorCode(connectorCode)
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
            assertTrue(ToolRegistry.parseToolSchema(spec("t", "cc", "ns", "c", "")).contains("additionalProperties"));
            assertTrue(ToolRegistry.parseToolSchema(spec("t", "cc", "ns", "c", "{\"type\":\"object\"}"))
                    .contains("\"properties\":{}"));
        }

        @Test
        @DisplayName("passes a real schema through unchanged")
        void passthrough() {
            String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}";
            assertEquals(schema, ToolRegistry.parseToolSchema(spec("t", "cc", "ns", "c", schema)));
        }
    }

    @Nested
    @DisplayName("build / resolve")
    class BuildResolve {
        @Test
        @DisplayName("namespaces LLM names and resolves back to backend routing")
        void resolves() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    spec("get_tasks", "board", "board", "conn-1", null),
                    spec("search", "mcp", "mcp_ctx7", "conn-2", null)));

            assertEquals(List.of("board__get_tasks", "mcp_ctx7__search"), reg.names());

            ToolRegistry.BackendTool bt = reg.resolve("mcp_ctx7__search");
            assertEquals("mcp", bt.connectorCode());
            assertEquals("search", bt.name());
            assertEquals("conn-2", bt.connectionId());
            assertNull(reg.resolve("unknown_tool"));
        }

        @Test
        @DisplayName("openWorldHint из аннотаций доезжает до BackendTool")
        void openWorldHint() {
            ConnectorToolSpec openWorld = spec("fetch", "mcp", "mcp", "conn-1", null).toBuilder()
                    .setAnnotations(ToolAnnotations.newBuilder().setOpenWorldHint(true))
                    .build();
            ToolRegistry reg = ToolRegistry.build(List.of(
                    openWorld,
                    spec("get_tasks", "board", "board", "conn-2", null)));

            assertTrue(reg.resolve("mcp__fetch").openWorld());
            assertFalse(reg.resolve("board__get_tasks").openWorld());
        }

        @Test
        @DisplayName("коллизия санитизированных имён получает суффикс, оба тула резолвятся в свои бэкенды")
        void collisionSuffixed() {
            // ns.a.b и (ns.a)+(b) санитизируются в одно имя ns__a__b.
            ToolRegistry reg = ToolRegistry.build(List.of(
                    spec("a.b", "first", "ns", "conn-1", null),
                    spec("b", "second", "ns.a", "conn-2", null)));

            assertEquals(List.of("ns__a__b", "ns__a__b_2"), reg.names());
            assertEquals("first", reg.resolve("ns__a__b").connectorCode());
            assertEquals("second", reg.resolve("ns__a__b_2").connectorCode());
        }

        @Test
        @DisplayName("llm_name from the backend is taken verbatim, the worker's own sanitizing is the fallback")
        void backendNameWins() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    spec("get_tasks", "board", "board", "conn-1", null).toBuilder().setLlmName("board__get_tasks_2").build(),
                    spec("search", "mcp", "mcp_ctx7", "conn-2", null)));

            assertEquals(List.of("board__get_tasks_2", "mcp_ctx7__search"), reg.names());
            assertEquals("board", reg.resolve("board__get_tasks_2").connectorCode());
        }

        @Test
        @DisplayName("the context-material marker in _meta reaches BackendTool")
        void materialFromMeta() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    spec("describe_tools", "tool-deferral", "tool-deferral", "c", null).toBuilder()
                            .putMeta(ToolRegistry.META_CONTEXT_MATERIAL, "tools").build(),
                    spec("load_skill", "skill-loader", "skill-loader", "c", null).toBuilder()
                            .putMeta(ToolRegistry.META_CONTEXT_MATERIAL, "skill").build(),
                    spec("now", "time", "time", "c", null)));

            assertEquals(ContextMaterial.TOOLS, reg.resolve("tool-deferral__describe_tools").material());
            assertEquals(ContextMaterial.SKILL, reg.resolve("skill-loader__load_skill").material());
            assertEquals(ContextMaterial.NONE, reg.resolve("time__now").material());
        }

        @Test
        @DisplayName("display names project an assistant's tool calls back to backend names")
        void displayNames() {
            ToolRegistry reg = ToolRegistry.build(List.of(spec("get_tasks", "board", "board", "c", null)));
            AgentChatMessage assistant = AgentChatMessage.assistant(null, false,
                    List.of(new AgentChatMessage.ToolCall("id1", "board__get_tasks", "{}")));
            assertEquals(List.of("get_tasks"), reg.displayNames(assistant));
        }
    }

    @Nested
    @DisplayName("progressive disclosure")
    class Disclosing {

        private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";

        private static ConnectorToolSpec lazy(String name) {
            return spec(name, "platform", "platform", "conn-1", null).toBuilder()
                    .setDisclosure(Disclosure.DISCLOSURE_LAZY).setSummary("short").build();
        }

        @Test
        @DisplayName("a LAZY spec is routed but not callable: absent from toolDefs, resolve answers null")
        void lazyIsNotCallable() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    lazy("agent_list"),
                    spec("now", "time", "time", "conn-2", null)));

            assertEquals(List.of("time__now"), reg.names());
            assertNull(reg.resolve("platform__agent_list"));
            assertEquals("agent_list", reg.displayName("platform__agent_list"));
        }

        @Test
        @DisplayName("disclose makes the tool callable with the full schema, in disclosure order after the eager ones")
        void discloseAddsToolDef() {
            ToolRegistry reg = ToolRegistry.build(List.of(
                    lazy("agent_list"), lazy("agent_get"), spec("now", "time", "time", "conn-2", null)));

            List<String> names = reg.disclose(List.of(
                    spec("agent_get", "platform", "platform", "conn-1", SCHEMA).toBuilder()
                            .setLlmName("platform__agent_get").setDescription("full description").build()));

            assertEquals(List.of("platform__agent_get"), names);
            assertEquals(List.of("time__now", "platform__agent_get"), reg.names());
            ToolDef def = reg.toolDefs().get(1);
            assertEquals("full description", def.description());
            assertEquals(SCHEMA, def.parametersJsonSchema());
            assertEquals("platform", reg.resolve("platform__agent_get").connectorCode());
            assertNull(reg.resolve("platform__agent_list"));
        }

        @Test
        @DisplayName("disclose is idempotent and registers a spec the run context never listed")
        void discloseIdempotentAndOpen() {
            ToolRegistry reg = ToolRegistry.build(List.of(lazy("agent_list")));
            ConnectorToolSpec full = spec("agent_list", "platform", "platform", "conn-1", SCHEMA).toBuilder()
                    .setLlmName("platform__agent_list").build();
            ConnectorToolSpec stranger = spec("extra", "platform", "platform", "conn-1", SCHEMA).toBuilder()
                    .setLlmName("platform__extra").build();

            reg.disclose(List.of(full));
            List<String> again = reg.disclose(List.of(full, stranger));

            assertEquals(List.of("platform__agent_list", "platform__extra"), again);
            assertEquals(List.of("platform__agent_list", "platform__extra"), reg.names());
            assertEquals("extra", reg.resolve("platform__extra").name());
        }

        @Test
        @DisplayName("toolDefs is a snapshot: the list handed to a call does not change under it")
        void toolDefsSnapshot() {
            ToolRegistry reg = ToolRegistry.build(List.of(lazy("agent_list")));
            List<ToolDef> before = reg.toolDefs();

            reg.disclose(List.of(spec("agent_list", "platform", "platform", "conn-1", SCHEMA).toBuilder()
                    .setLlmName("platform__agent_list").build()));

            assertTrue(before.isEmpty());
            assertEquals(1, reg.toolDefs().size());
        }
    }
}
