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
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService.WaitOutcome;
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
import ru.agimate.controlapi.controller.mcp.dto.TaskResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private ToolCallLogService toolCallLogService;
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
        when(rateLimiter.tryAcquire(eq(InboundRateLimiter.Scope.MCP_TASK), any())).thenReturn(true);
        when(toolCallLogService.countLiveDetached(eq(AGENT_ID), any())).thenReturn(0L);
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
        @DisplayName("initialize отдаёт единственную ревизию, tools и расширение tasks")
        void initialize() {
            InitializeResult result = (InitializeResult) call("initialize", Map.of()).result();

            assertEquals("2026-07-28", result.protocolVersion());
            assertTrue(result.capabilities().containsKey("tools"));
            assertEquals(Map.of("io.modelcontextprotocol/tasks", Map.of()),
                    result.capabilities().get("extensions"));
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
                    .thenReturn(new WaitOutcome.Completed(new ToolResult("ext-1", "telegram", "{\"ok\":true}", null)));

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
                    .thenReturn(new WaitOutcome.Completed(new ToolResult("ext-1", "telegram", "{\"messageId\":7}", null)));

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
                    .thenReturn(new WaitOutcome.Completed(new ToolResult("ext-1", "telegram", null, "chat not found")));

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

    @Nested
    @DisplayName("таски (расширение io.modelcontextprotocol/tasks)")
    class Tasks {

        private static final Map<String, Object> TASKS_META = Map.of(
                "io.modelcontextprotocol/clientCapabilities", Map.of(
                        "extensions", Map.of("io.modelcontextprotocol/tasks", Map.of())));

        private final ToolCallLog pending = ToolCallLog.builder()
                .id(UUID.randomUUID()).agentId(AGENT_ID)
                .externalId("ext-1").name("send").connectorCode("telegram")
                .connectionId(CONNECTION_ID.toString())
                .build();

        private Map<String, Object> capableCall() {
            return Map.of("name", "telegram_bot__send", "arguments", Map.of(), "_meta", TASKS_META);
        }

        private Map<String, Object> taskParams(String taskId) {
            return Map.of("taskId", taskId, "_meta", TASKS_META);
        }

        private ToolCallLog task(LocalDateTime finishAt, LocalDateTime cancelRequestedAt) {
            ToolCallLog row = ToolCallLog.builder()
                    .id(UUID.randomUUID()).agentId(AGENT_ID)
                    .externalId("task-1").name("send").connectorCode("telegram")
                    .connectionId(CONNECTION_ID.toString())
                    .detachedAt(LocalDateTime.now().minusSeconds(30))
                    .finishAt(finishAt).cancelRequestedAt(cancelRequestedAt)
                    .output(finishAt != null && cancelRequestedAt == null ? "{\"ok\":true}" : null)
                    .build();
            row.setCreatedAt(LocalDateTime.now().minusMinutes(1));
            row.setUpdatedAt(LocalDateTime.now());
            when(toolCallLogService.findByExternalIdAndAgentId("task-1", AGENT_ID))
                    .thenReturn(Optional.of(row));
            return row;
        }

        @Test
        @DisplayName("клиент без капабилити на таймауте получает isError, а не таск")
        void withoutCapabilityKeepsTheOldTimeout() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(pending);
            when(toolExecutionService.executeWithTimeout(eq(pending), eq(Duration.ofSeconds(60))))
                    .thenReturn(new WaitOutcome.StillRunning());

            ToolCallResult result = (ToolCallResult) call("tools/call",
                    Map.of("name", "telegram_bot__send", "arguments", Map.of())).result();

            assertTrue(result.isError());
            assertTrue(result.content().get(0).text().contains("timed out"));
            verify(toolCallLogService, never()).detach(any(), any());
        }

        @Test
        @DisplayName("объявивший капабилити после grace получает CreateTaskResult")
        void slowCapableCallBecomesTask() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(pending);
            when(toolExecutionService.executeWithTimeout(eq(pending), eq(Duration.ofSeconds(10))))
                    .thenReturn(new WaitOutcome.StillRunning());
            ToolCallLog detached = task(null, null);
            when(toolCallLogService.detach(AGENT_ID, "ext-1")).thenReturn(detached);

            TaskResult result = (TaskResult) call("tools/call", capableCall()).result();

            assertEquals("task", result.resultType());
            assertEquals("working", result.status());
            assertEquals("task-1", result.taskId());
            assertEquals(5000, result.pollIntervalMs());
            assertNotNull(result.ttlMs());
        }

        @Test
        @DisplayName("тул успел на границе grace: гонку выиграл результат, таска нет")
        void graceRaceHandsBackThePlainResult() {
            catalogWith(spec(null));
            when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenReturn(pending);
            when(toolExecutionService.executeWithTimeout(eq(pending), any(Duration.class)))
                    .thenReturn(new WaitOutcome.StillRunning());
            ToolCallLog finished = ToolCallLog.builder()
                    .id(pending.getId()).agentId(AGENT_ID).externalId("ext-1")
                    .connectorCode("telegram").finishAt(LocalDateTime.now())
                    .output("{\"ok\":true}")
                    .build();
            when(toolCallLogService.detach(AGENT_ID, "ext-1")).thenReturn(finished);

            ToolCallResult result = (ToolCallResult) call("tools/call", capableCall()).result();

            assertEquals("{\"ok\":true}", result.content().get(0).text());
            assertNull(result.isError());
        }

        @Test
        @DisplayName("потолок живых тасков: отказ до создания лога и старта исполнения")
        void capRefusesBeforeStart() {
            catalogWith(spec(null));
            when(toolCallLogService.countLiveDetached(eq(AGENT_ID), any())).thenReturn(10L);

            ToolCallResult result = (ToolCallResult) call("tools/call", capableCall()).result();

            assertTrue(result.isError());
            assertTrue(result.content().get(0).text().contains("Too many running tasks"));
            verify(agentToolCallService, never()).authorizeToolCall(any(), any());
            verify(toolExecutionService, never()).executeWithTimeout(any(), any());
        }

        @Test
        @DisplayName("tasks/get без капабилити → -32003 с requiredCapabilities в data")
        void taskMethodWithoutCapability() {
            JsonRpcResponse response = call("tasks/get", Map.of("taskId", "task-1"));

            assertEquals(JsonRpcError.MISSING_CLIENT_CAPABILITY, response.error().code());
            assertNotNull(response.error().data());
        }

        @Test
        @DisplayName("неизвестный taskId → -32602; чужой неотличим от несуществующего")
        void taskNotFound() {
            when(toolCallLogService.findByExternalIdAndAgentId("task-1", AGENT_ID))
                    .thenReturn(Optional.empty());

            assertEquals(JsonRpcError.INVALID_PARAMS,
                    call("tasks/get", taskParams("task-1")).error().code());
        }

        @Test
        @DisplayName("синхронно отданный вызов — не таск: detached_at IS NULL → -32602")
        void aFastCallIsNotATask() {
            ToolCallLog plain = ToolCallLog.builder()
                    .id(UUID.randomUUID()).agentId(AGENT_ID).externalId("task-1")
                    .finishAt(LocalDateTime.now()).output("{}")
                    .build();
            when(toolCallLogService.findByExternalIdAndAgentId("task-1", AGENT_ID))
                    .thenReturn(Optional.of(plain));

            assertEquals(JsonRpcError.INVALID_PARAMS,
                    call("tasks/get", taskParams("task-1")).error().code());
        }

        @Test
        @DisplayName("не завершён → working, даже со взведённой отменой")
        void getWorking() {
            task(null, LocalDateTime.now());

            TaskResult result = (TaskResult) call("tasks/get", taskParams("task-1")).result();

            assertEquals("working", result.status());
            assertEquals("complete", result.resultType());
            assertNull(result.result());
        }

        @Test
        @DisplayName("завершён без отмены → completed с инлайненным результатом")
        void getCompleted() {
            task(LocalDateTime.now(), null);
            when(toolCatalog.forAgent(agent)).thenReturn(Map.of());

            TaskResult result = (TaskResult) call("tasks/get", taskParams("task-1")).result();

            assertEquals("completed", result.status());
            assertEquals("{\"ok\":true}", result.result().content().get(0).text());
        }

        @Test
        @DisplayName("завершён со штампом отмены → cancelled, результат не отдаётся")
        void getCancelled() {
            task(LocalDateTime.now(), LocalDateTime.now().minusSeconds(5));

            TaskResult result = (TaskResult) call("tasks/get", taskParams("task-1")).result();

            assertEquals("cancelled", result.status());
            assertNull(result.result());
        }

        @Test
        @DisplayName("старше TTL → -32602 expired, даже если так и висит working")
        void getExpired() {
            ToolCallLog orphan = task(null, null);
            orphan.setCreatedAt(LocalDateTime.now().minusHours(25));

            JsonRpcResponse response = call("tasks/get", taskParams("task-1"));

            assertEquals(JsonRpcError.INVALID_PARAMS, response.error().code());
            assertTrue(response.error().message().contains("expired"));
        }

        @Test
        @DisplayName("отмена бегущего: штамп ложится, ответ — пустой ack")
        void cancelRunning() {
            ToolCallLog running = task(null, null);
            when(toolCallLogService.requestCancel(running.getId())).thenReturn(true);

            assertInstanceOf(EmptyResult.class, call("tasks/cancel", taskParams("task-1")).result());
            verify(toolCallLogService).requestCancel(running.getId());
        }

        @Test
        @DisplayName("отмена завершённого: 0 строк, тот же ack — таск остаётся completed")
        void cancelFinished() {
            ToolCallLog finished = task(LocalDateTime.now(), null);
            when(toolCallLogService.requestCancel(finished.getId())).thenReturn(false);

            assertInstanceOf(EmptyResult.class, call("tasks/cancel", taskParams("task-1")).result());
        }

        @Test
        @DisplayName("tasks/update: input_required не производим — пустой ack")
        void updateAcks() {
            task(null, null);

            assertInstanceOf(EmptyResult.class, call("tasks/update", taskParams("task-1")).result());
        }

        @Test
        @DisplayName("лимит поллов исчерпан → 429")
        void pollRateLimited() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.MCP_TASK, AGENT_ID)).thenReturn(false);

            assertThrows(TooManyRequestsStatusException.class,
                    () -> call("tasks/get", taskParams("task-1")));
        }
    }
}
