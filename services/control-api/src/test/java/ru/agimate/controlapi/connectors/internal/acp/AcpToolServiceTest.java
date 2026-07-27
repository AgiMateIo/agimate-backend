package ru.agimate.controlapi.connectors.internal.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry.ClientCapabilities;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AcpToolService (IDE-тулы через живой WebSocket)")
class AcpToolServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final ClientCapabilities FULL = new ClientCapabilities(true, true, true);

    @Mock
    private AcpSessionRegistry registry;

    private AcpConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new AcpConnectorService(new AcpToolService(registry), registry);
        lenient().when(registry.isConnected(SESSION_ID)).thenReturn(true);
    }

    /** Env с sessionId — как его собирает ToolExecutionService для ACP-рана. */
    private static ConnectorEnv env() {
        return new ConnectorEnv("conn", UUID.randomUUID(), UUID.randomUUID(), null, null, SESSION_ID, Map.of(), null);
    }

    private static CompletableFuture<JsonNode> reply(String json) {
        return CompletableFuture.completedFuture(JsonUtils.toJsonNode(json));
    }

    private void stub(String method, CompletableFuture<JsonNode> future) {
        lenient().when(registry.request(eq(SESSION_ID), eq(method), any())).thenReturn(future);
    }

    private void allowPermission() {
        stub("session/request_permission", reply("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow\"}}"));
    }

    @Nested
    @DisplayName("read_file")
    class Read {

        @Test
        @DisplayName("вызывает fs/read_text_file и возвращает content")
        void reads() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            stub("fs/read_text_file", reply("{\"content\":\"line1\\nline2\"}"));

            Map<String, Object> result = handler.executeTool(env(), "read_file",
                    Map.of("path", "/home/u/main.py", "line", 2, "limit", 10));

            assertEquals("line1\nline2", result.get("content"));
            verify(registry).request(eq(SESSION_ID), eq("fs/read_text_file"), any());
        }

        @Test
        @DisplayName("нет fs.readTextFile capability → ConnectorException, вызова нет")
        void noCapability() {
            when(registry.capabilities(SESSION_ID)).thenReturn(new ClientCapabilities(false, false, false));

            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "read_file",
                    Map.of("path", "/a")));
            verify(registry, never()).request(any(), eq("fs/read_text_file"), any());
        }

        @Test
        @DisplayName("относительный путь отклоняется")
        void rejectsRelativePath() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "read_file",
                    Map.of("path", "relative/path")));
        }

        @Test
        @DisplayName("сессия есть, но соединение оборвано → ConnectorException 'not connected', без capability-проверки")
        void notConnected() {
            when(registry.isConnected(SESSION_ID)).thenReturn(false);

            ConnectorException ex = assertThrows(ConnectorException.class,
                    () -> handler.executeTool(env(), "read_file", Map.of("path", "/a")));
            assertTrue(ex.getMessage().toLowerCase().contains("not connected"));
            verify(registry, never()).capabilities(any());
        }

        @Test
        @DisplayName("нет sessionId в env (не-ACP ран) → ConnectorException")
        void noSession() {
            ConnectorEnv noSession = new ConnectorEnv("conn", UUID.randomUUID(), UUID.randomUUID(),
                    null, null, null, Map.of(), null);
            assertThrows(ConnectorException.class, () -> handler.executeTool(noSession, "read_file",
                    Map.of("path", "/a")));
        }
    }

    @Nested
    @DisplayName("write_file")
    class Write {

        @Test
        @DisplayName("спрашивает разрешение, затем fs/write_text_file")
        void writesAfterPermission() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            allowPermission();
            stub("fs/write_text_file", reply("null"));

            Map<String, Object> result = handler.executeTool(env(), "write_file",
                    Map.of("path", "/a/b.txt", "content", "hello"));

            assertEquals(true, result.get("ok"));
            verify(registry).request(eq(SESSION_ID), eq("session/request_permission"), any());
            verify(registry).request(eq(SESSION_ID), eq("fs/write_text_file"), any());
        }

        @Test
        @DisplayName("пользователь отклонил → ConnectorException, записи нет")
        void userRejected() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            stub("session/request_permission", reply("{\"outcome\":{\"outcome\":\"cancelled\"}}"));

            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "write_file",
                    Map.of("path", "/a/b.txt", "content", "x")));
            verify(registry, never()).request(any(), eq("fs/write_text_file"), any());
        }
    }

    @Nested
    @DisplayName("run_command")
    class Run {

        @Test
        @DisplayName("create → wait → output → release, возвращает exitCode и output")
        void happyPath() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            allowPermission();
            stub("terminal/create", reply("{\"terminalId\":\"t1\"}"));
            stub("terminal/wait_for_exit", reply("{\"exitCode\":0}"));
            stub("terminal/output", reply("{\"output\":\"ok\",\"truncated\":false,\"exitStatus\":{\"exitCode\":0}}"));
            stub("terminal/release", reply("null"));

            Map<String, Object> result = handler.executeTool(env(), "run_command",
                    Map.of("command", "ls", "args", java.util.List.of("-la")));

            assertEquals("ok", result.get("output"));
            assertEquals(0, result.get("exitCode"));
            verify(registry).request(eq(SESSION_ID), eq("terminal/release"), any());
        }

        @Test
        @DisplayName("сбой ожидания → kill, читаем частичный вывод, timedOut=true")
        void waitFailureKills() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            allowPermission();
            stub("terminal/create", reply("{\"terminalId\":\"t1\"}"));
            stub("terminal/wait_for_exit", CompletableFuture.failedFuture(new IllegalStateException("timeout")));
            stub("terminal/kill", reply("null"));
            stub("terminal/output", reply("{\"output\":\"partial\",\"truncated\":true}"));
            stub("terminal/release", reply("null"));

            Map<String, Object> result = handler.executeTool(env(), "run_command", Map.of("command", "sleep"));

            assertEquals("partial", result.get("output"));
            assertEquals(true, result.get("timedOut"));
            verify(registry).request(eq(SESSION_ID), eq("terminal/kill"), any());
            verify(registry).request(eq(SESSION_ID), eq("terminal/release"), any());
        }

        @Test
        @DisplayName("модель не задала cwd → команда уходит в корень проекта сессии, а не в дефолт клиента")
        void defaultsToSessionRoot() {
            terminalHappyPath();
            when(registry.cwd(SESSION_ID)).thenReturn("/home/u/project");

            handler.executeTool(env(), "run_command", Map.of("command", "ls"));

            assertEquals("/home/u/project", createParams().get("cwd"));
        }

        @Test
        @DisplayName("явный cwd от модели побеждает корень сессии")
        void explicitCwdWins() {
            terminalHappyPath();

            handler.executeTool(env(), "run_command",
                    Map.of("command", "ls", "cwd", "/home/u/project/sub"));

            assertEquals("/home/u/project/sub", createParams().get("cwd"));
            verify(registry, never()).cwd(any());
        }

        @Test
        @DisplayName("клиент не дал корня и модель тоже → terminal/create без cwd (решает клиент)")
        void noRootNoCwd() {
            terminalHappyPath();
            when(registry.cwd(SESSION_ID)).thenReturn(null);

            handler.executeTool(env(), "run_command", Map.of("command", "ls"));

            assertFalse(createParams().containsKey("cwd"));
        }

        @Test
        @DisplayName("относительный cwd отклоняется до подтверждения и создания терминала")
        void relativeCwdRejected() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);

            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "run_command",
                    Map.of("command", "ls", "cwd", "sub/dir")));
            verify(registry, never()).request(any(), any(), any());
        }

        private void terminalHappyPath() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            allowPermission();
            stub("terminal/create", reply("{\"terminalId\":\"t1\"}"));
            stub("terminal/wait_for_exit", reply("{\"exitCode\":0}"));
            stub("terminal/output", reply("{\"output\":\"ok\",\"exitStatus\":{\"exitCode\":0}}"));
            stub("terminal/release", reply("null"));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> createParams() {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(registry).request(eq(SESSION_ID), eq("terminal/create"), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("IDE отключилась (request бросает) → ConnectorException")
        void ideDisconnected() {
            when(registry.capabilities(SESSION_ID)).thenReturn(FULL);
            allowPermission();
            when(registry.request(eq(SESSION_ID), eq("terminal/create"), any()))
                    .thenThrow(new IllegalStateException("IDE session is not connected"));

            ConnectorException ex = assertThrows(ConnectorException.class,
                    () -> handler.executeTool(env(), "run_command", Map.of("command", "ls")));
            assertTrue(ex.getMessage().toLowerCase().contains("not connected"));
        }
    }

    @Nested
    @DisplayName("MCP-тулы сессии (проброшенные из IDE)")
    class Mcp {

        private static final String TOOL = "tinvest__get_portfolio";

        private void stubTool(boolean readOnly) {
            var spec = new ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec(
                    TOOL, null, "d", null, null,
                    new ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec(readOnly, !readOnly, false, true),
                    null, null);
            lenient().when(registry.mcpToolRef(SESSION_ID, TOOL))
                    .thenReturn(new AcpSessionRegistry.McpToolRef("tinvest", "get_portfolio"));
            lenient().when(registry.mcpToolSpec(SESSION_ID, TOOL)).thenReturn(spec);
        }

        @Test
        @DisplayName("read-only тул: без подтверждения, mcp/call_tool с server+raw-именем, результат наверх")
        void readOnlyNoPermission() {
            stubTool(true);
            stub("mcp/call_tool", reply("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));

            Map<String, Object> result = handler.executeTool(env(), TOOL, Map.of("account", "1"));

            assertTrue(result.containsKey("content"));
            verify(registry).request(eq(SESSION_ID), eq("mcp/call_tool"), any());
            verify(registry, never()).request(eq(SESSION_ID), eq("session/request_permission"), any());
        }

        @Test
        @DisplayName("мутирующий тул: спрашивает подтверждение перед mcp/call_tool")
        void mutatingAsksPermission() {
            stubTool(false);
            allowPermission();
            stub("mcp/call_tool", reply("{\"content\":[]}"));

            handler.executeTool(env(), TOOL, Map.of());

            verify(registry).request(eq(SESSION_ID), eq("session/request_permission"), any());
            verify(registry).request(eq(SESSION_ID), eq("mcp/call_tool"), any());
        }

        @Test
        @DisplayName("отказ пользователя на мутирующий тул → ConnectorException, вызова нет")
        void mutatingRejected() {
            stubTool(false);
            stub("session/request_permission", reply("{\"outcome\":{\"outcome\":\"cancelled\"}}"));

            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), TOOL, Map.of()));
            verify(registry, never()).request(eq(SESSION_ID), eq("mcp/call_tool"), any());
        }

        @Test
        @DisplayName("неизвестный MCP-тул (нет ref) → ConnectorException")
        void unknownTool() {
            when(registry.mcpToolRef(SESSION_ID, "ghost__x")).thenReturn(null);
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "ghost__x", Map.of()));
        }

        @Test
        @DisplayName("getTools(env) мёржит фиксированные тулы и session MCP-тулы")
        void getToolsMerges() {
            var mcpSpec = new ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec(
                    TOOL, null, "d", null, null, null, null, null);
            when(registry.mcpToolSpecs(SESSION_ID)).thenReturn(Map.of(TOOL, mcpSpec));

            var tools = handler.getTools(env());

            assertTrue(tools.containsKey("read_file"));
            assertTrue(tools.containsKey(TOOL));
        }
    }

    @Nested
    @DisplayName("промпт-блок корня проекта")
    class Blocks {

        @Test
        @DisplayName("живая IDE-сессия с корнем → SYSTEM-блок с путём проекта")
        void rootBlock() {
            when(registry.cwd(SESSION_ID)).thenReturn("/home/u/project");

            var blocks = handler.promptBlocks(env());

            assertEquals(1, blocks.size());
            assertEquals(PromptBlock.Placement.SYSTEM, blocks.getFirst().placement());
            assertTrue(blocks.getFirst().content().contains("/home/u/project"));
        }

        @Test
        @DisplayName("ран не из IDE (нет sessionId) → блока нет, реестр не трогаем")
        void noSessionNoBlock() {
            ConnectorEnv webchat = new ConnectorEnv(
                    "conn", UUID.randomUUID(), UUID.randomUUID(), null, null, null, Map.of(), null);

            assertTrue(handler.promptBlocks(webchat).isEmpty());
            verify(registry, never()).cwd(any());
        }

        @Test
        @DisplayName("сессия чужая/мертвая (корня в реестре нет) → блока нет")
        void unknownSessionNoBlock() {
            when(registry.cwd(SESSION_ID)).thenReturn(null);

            assertTrue(handler.promptBlocks(env()).isEmpty());
        }
    }
}
