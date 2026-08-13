package ru.agimate.controlapi.service.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.controller.mcp.dto.DiscoverResult;
import ru.agimate.controlapi.controller.mcp.dto.EmptyResult;
import ru.agimate.controlapi.controller.mcp.dto.InitializeResult;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcError;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.controller.mcp.dto.ToolCallResult;
import ru.agimate.controlapi.controller.mcp.dto.ToolsListResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpService — tools/list и tools/call")
class McpServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("mcp-agent", AGENT_ID, USER_ID);

    @Mock
    private AgentService agentService;
    @Mock
    private McpToolCatalog toolCatalog;
    @Mock
    private AgentToolCallService agentToolCallService;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private InboundRateLimiter rateLimiter;

    @InjectMocks
    private McpService mcpService;

    private final Agent agent = Agent.builder()
            .id(AGENT_ID).userId(USER_ID).name("mcp-agent").type(AgentType.MCP).build();

    @BeforeEach
    void setUp() {
        when(agentService.findById(AGENT_ID)).thenReturn(agent);
        when(rateLimiter.tryAcquire(eq(InboundRateLimiter.Scope.MCP_CALL), any())).thenReturn(true);
    }

    private static ConnectorToolSpec spec(JsonSchema outputSchema) {
        return new ConnectorToolSpec("send", null, "Send a message",
                JsonSchema.any(null), outputSchema, null, null, null);
    }

    private void catalogWith(ConnectorToolSpec spec) {
        when(toolCatalog.forAgent(agent)).thenReturn(Map.of("telegram_bot__send",
                new McpToolCatalog.ToolEntry(CONNECTION_ID, "telegram", "send", spec)));
    }

    private JsonRpcResponse call(String method, Map<String, Object> params) {
        Optional<JsonRpcResponse> response =
                mcpService.handle(PRINCIPAL, new JsonRpcRequest("2.0", 1, method, params));
        assertTrue(response.isPresent(), "на запрос с id должен быть ответ");
        return response.get();
    }

    @Nested
    @DisplayName("протокол")
    class Protocol {

        @Test
        @DisplayName("initialize отдаёт единственную поддерживаемую ревизию и capability tools")
        void initialize() {
            InitializeResult result = (InitializeResult) call("initialize", Map.of()).result();

            assertEquals("2026-07-28", result.protocolVersion());
            assertEquals(Map.of("tools", Map.of()), result.capabilities());
        }

        @Test
        @DisplayName("нотификация (без id) — ответа нет")
        void notificationIsNotAnswered() {
            Optional<JsonRpcResponse> response = mcpService.handle(PRINCIPAL,
                    new JsonRpcRequest("2.0", null, "notifications/initialized", null));

            assertTrue(response.isEmpty());
        }

        @Test
        @DisplayName("server/discover отдаёт ревизии, капабилити и serverInfo в _meta")
        void serverDiscover() {
            DiscoverResult result = (DiscoverResult) call("server/discover", Map.of()).result();

            assertEquals(List.of("2026-07-28"), result.supportedVersions());
            assertTrue(result.capabilities().containsKey("tools"));
            assertNotNull(result.meta().get("io.modelcontextprotocol/serverInfo"));
        }

        @Test
        @DisplayName("ping — пустой результат, а не голая мапа")
        void ping() {
            assertInstanceOf(EmptyResult.class, call("ping", Map.of()).result());
        }

        @Test
        @DisplayName("неизвестный метод → -32601")
        void unknownMethod() {
            assertEquals(JsonRpcError.METHOD_NOT_FOUND, call("resources/list", Map.of()).error().code());
        }
    }

    @Nested
    @DisplayName("tools/list")
    class ToolsList {

        @Test
        @DisplayName("имена берутся из каталога, timeoutSeconds наружу не уезжает")
        void listsCatalogNames() {
            catalogWith(spec(null));

            ToolsListResult result = (ToolsListResult) call("tools/list", Map.of()).result();

            assertEquals(1, result.tools().size());
            assertEquals("telegram_bot__send", result.tools().get(0).name());
            assertEquals("Send a message", result.tools().get(0).description());
        }
    }

    @Nested
    @DisplayName("tools/call")
    class ToolsCall {

        private final ToolCallLog toolCallLog = ToolCallLog.builder()
                .agentId(AGENT_ID).connectorCode("telegram").name("send").externalId("ext-1").build();

        @Test
        @DisplayName("тула нет в каталоге → -32602, ничего не исполняется")
        void unknownTool() {
            when(toolCatalog.forAgent(agent)).thenReturn(Map.of());

            JsonRpcResponse response = call("tools/call", Map.of("name", "nope", "arguments", Map.of()));

            assertEquals(JsonRpcError.INVALID_PARAMS, response.error().code());
            verify(toolExecutionService, never()).executeWithTimeout(any(), any());
        }

        @Test
        @DisplayName("успех → content с выводом; structuredContent только при outputSchema")
        void success() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(toolCallLog);
            when(toolExecutionService.executeWithTimeout(eq(toolCallLog), any(Duration.class)))
                    .thenReturn(new ToolResult("ext-1", "telegram", "{\"ok\":true}", null));

            ToolCallResult result = (ToolCallResult) call("tools/call",
                    Map.of("name", "telegram_bot__send", "arguments", Map.of("text", "hi"))).result();

            assertEquals("{\"ok\":true}", result.content().get(0).text());
            assertNull(result.isError());
            assertNull(result.structuredContent(), "без outputSchema структурированного вывода нет");
        }

        @Test
        @DisplayName("тул с outputSchema → structuredContent разобран")
        void structuredOutput() {
            catalogWith(spec(JsonSchema.any(null)));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(toolCallLog);
            when(toolExecutionService.executeWithTimeout(eq(toolCallLog), any(Duration.class)))
                    .thenReturn(new ToolResult("ext-1", "telegram", "{\"messageId\":7}", null));

            ToolCallResult result = (ToolCallResult) call("tools/call",
                    Map.of("name", "telegram_bot__send", "arguments", Map.of())).result();

            assertNotNull(result.structuredContent());
            assertEquals(7, result.structuredContent().get("messageId"));
        }

        @Test
        @DisplayName("ошибка тула → isError, а не транспортная ошибка")
        void toolFailureIsResult() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(toolCallLog);
            when(toolExecutionService.executeWithTimeout(eq(toolCallLog), any(Duration.class)))
                    .thenReturn(new ToolResult("ext-1", "telegram", null, "chat not found"));

            ToolCallResult result = (ToolCallResult) call("tools/call",
                    Map.of("name", "telegram_bot__send", "arguments", Map.of())).result();

            assertTrue(result.isError());
            assertEquals("chat not found", result.content().get(0).text());
        }

        @Test
        @DisplayName("запрет политики на вызове (params_filter) → isError с причиной")
        void deniedByPolicy() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any()))
                    .thenThrow(new ForbiddenStatusException("Tool arguments rejected by params_filter"));

            ToolCallResult result = (ToolCallResult) call("tools/call",
                    Map.of("name", "telegram_bot__send", "arguments", Map.of())).result();

            assertTrue(result.isError());
            assertTrue(result.content().get(0).text().contains("params_filter"));
        }

        @Test
        @DisplayName("лимит вызовов исчерпан → 429 до обращения к каталогу")
        void rateLimited() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.MCP_CALL, AGENT_ID)).thenReturn(false);

            assertThrows(TooManyRequestsStatusException.class,
                    () -> call("tools/call", Map.of("name", "telegram_bot__send")));
            verify(toolCatalog, never()).forAgent(any());
        }
    }
}
