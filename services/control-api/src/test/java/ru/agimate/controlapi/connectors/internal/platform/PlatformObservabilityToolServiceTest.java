package ru.agimate.controlapi.connectors.internal.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.WebhookDeliveryLog;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRunProjection;
import ru.agimate.controlapi.database.projections.TriggerLogWithAgentsCountProjection;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogRepository;
import ru.agimate.controlapi.database.repositories.WebhookDeliveryLogRepository;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.trigger.RunCancellationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PlatformObservabilityToolService")
class PlatformObservabilityToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final AgentRunTurnRepository agentRunTurnRepository = mock(AgentRunTurnRepository.class);
    private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
    private final ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
    private final TriggerLogRepository triggerLogRepository = mock(TriggerLogRepository.class);
    private final WebhookDeliveryLogRepository webhookDeliveryLogRepository = mock(WebhookDeliveryLogRepository.class);
    private final RunCancellationService runCancellationService = mock(RunCancellationService.class);

    private final PlatformObservabilityToolService tools = new PlatformObservabilityToolService(
            agentRunRepository, agentRunTurnRepository, agentSessionService, toolCallLogRepository,
            triggerLogRepository, webhookDeliveryLogRepository, runCancellationService);
    private final PlatformConnectorService handler = new PlatformConnectorService(
            mock(PlatformAgentToolService.class), mock(PlatformConnectionToolService.class), mock(PlatformLlmToolService.class),
            mock(PlatformWorkspaceToolService.class), tools);

    /** Вызов от имени владельца (userId задан, агент-инициатор отсутствует — обычный список/чтение). */
    private static ConnectorEnv env() {
        return new ConnectorEnv(null, USER_ID, null, null, null, null, Map.of(), null);
    }

    /** Строка listing'а ранов: проекция с фиксированными значениями для проверки маппинга. */
    private static AgentRunProjection runProjection(UUID runId, UUID sessionId) {
        AgentRunProjection p = mock(AgentRunProjection.class);
        when(p.getId()).thenReturn(runId);
        when(p.getName()).thenReturn("on_new_ticket");
        when(p.getConnectorCode()).thenReturn("telegram");
        when(p.getConnectionId()).thenReturn("conn-1");
        when(p.getStatus()).thenReturn(RunStatus.RUNNING);
        when(p.getResult()).thenReturn("ok");
        when(p.getSessionId()).thenReturn(sessionId);
        when(p.getMainRunId()).thenReturn(null);
        when(p.getSteeredAt()).thenReturn(LocalDateTime.of(2025, 1, 2, 3, 4));
        when(p.getTurnsIntact()).thenReturn(true);
        when(p.getTurnsCount()).thenReturn(4L);
        when(p.getLastActivityAt()).thenReturn(LocalDateTime.of(2025, 1, 1, 0, 0));
        return p;
    }

    /** Владельческая проверка (owner check) рана: тот же запрос, что у manage getRun. */
    private static void stubOwnedRun(AgentRunRepository repository, UUID runId, AgentRunProjection projection) {
        when(repository.findRunsWithFilters(eq(USER_ID), eq(runId), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(projection)));
    }

    @Nested
    @DisplayName("list_runs")
    class ListRuns {

        @Test
        @DisplayName("ключ в финальном ответе рана вырезается из листинга")
        void resultQuotingAKeyIsRedacted() {
            UUID runId = UUID.randomUUID();
            AgentRunProjection projection = runProjection(runId, UUID.randomUUID());
            when(projection.getResult()).thenReturn(
                    "created agent, key: agntapLrNHYBw8f3QtfDE9ueFPWbejAPskkSl21TwPYo9PHII1Oc6UfK_DD4CSDl");
            when(agentRunRepository.findRunsWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(projection)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_runs", Map.of());

            String text = (String) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("result");
            assertFalse(text.contains("agntapLr"));
            assertTrue(text.contains("[key redacted]"));
        }

        @Test
        @DisplayName("маппит проекцию и пробрасывает фильтры")
        void mapsProjectionAndPassesFilters() {
            UUID agentId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            // The projection is built before the stubbing — a nested when() inside thenReturn()'s
            // argument would leave the outer stubbing unfinished.
            AgentRunProjection projection = runProjection(runId, sessionId);
            when(agentRunRepository.findRunsWithFilters(eq(USER_ID), isNull(), eq(agentId), eq(sessionId),
                    isNull(), eq("telegram"), eq("conn-1"), eq("tick"), eq(RunStatus.RUNNING),
                    any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(projection)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_runs",
                    Map.of("agentId", agentId.toString(), "sessionId", sessionId.toString(),
                            "connectorCode", "telegram", "connectionId", "conn-1", "name", "tick",
                            "status", "RUNNING"));

            List<?> items = (List<?>) result.get("items");
            assertEquals(1, items.size());
            Map<?, ?> brief = (Map<?, ?>) items.getFirst();
            assertEquals(runId.toString(), brief.get("id"));
            assertEquals("on_new_ticket", brief.get("triggerName"));
            assertEquals("telegram", brief.get("connectorCode"));
            assertEquals("conn-1", brief.get("connectionId"));
            assertEquals("RUNNING", brief.get("status"));
            assertEquals("ok", brief.get("result"));
            assertEquals(sessionId.toString(), brief.get("sessionId"));
            assertNull(brief.get("mainRunId"));
            assertEquals(Boolean.TRUE, brief.get("steered"));
            assertEquals(Boolean.TRUE, brief.get("turnsIntact"));
            assertEquals(4L, ((Number) brief.get("turnsCount")).longValue());
            assertEquals("2025-01-01T00:00", brief.get("lastActivityAt"));
        }

        @Test
        @DisplayName("статус фильтра валидируется: мусор — ConnectorException без обращения к БД")
        void garbageStatusRejected() {
            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "list_runs",
                    Map.of("status", "garbage")));
            assertTrue(ex.getMessage().contains("ENQUEUED"));
            assertTrue(ex.getMessage().contains("CANCELLED"));
            verifyNoInteractions(agentRunRepository);
        }
    }

    @Nested
    @DisplayName("get_run")
    class GetRun {

        @Test
        @DisplayName("чужой/отсутствующий ран — ConnectorException")
        void foreignRunReadsAsNotFound() {
            UUID runId = UUID.randomUUID();
            when(agentRunRepository.findRunsWithFilters(eq(USER_ID), eq(runId), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "get_run",
                    Map.of("runId", runId.toString())));
            assertTrue(ex.getMessage().contains("Run not found"));
        }

        @Test
        @DisplayName("свой ран возвращается той же проекцией, что и список")
        void ownRunReturned() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run",
                    Map.of("runId", runId.toString()));

            assertEquals(runId.toString(), result.get("id"));
            assertEquals("RUNNING", result.get("status"));
        }
    }

    @Nested
    @DisplayName("cancel_run")
    class CancelRun {

        @Test
        @DisplayName("возвращает поля CancelResult")
        void returnsCancelResultFields() {
            UUID runId = UUID.randomUUID();
            when(runCancellationService.cancelRun(runId, USER_ID))
                    .thenReturn(new RunCancellationService.CancelResult(RunStatus.RUNNING, true));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "cancel_run",
                    Map.of("runId", runId.toString()));

            assertEquals("RUNNING", result.get("status"));
            assertEquals(Boolean.TRUE, result.get("requested"));
            assertEquals(Boolean.FALSE, result.get("alreadyFinished"));
        }

        @Test
        @DisplayName("уже завершённый ран — alreadyFinished=true, requested=false")
        void finishedRunReported() {
            UUID runId = UUID.randomUUID();
            when(runCancellationService.cancelRun(runId, USER_ID))
                    .thenReturn(new RunCancellationService.CancelResult(RunStatus.DONE, false));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "cancel_run",
                    Map.of("runId", runId.toString()));

            assertEquals("DONE", result.get("status"));
            assertEquals(Boolean.FALSE, result.get("requested"));
            assertEquals(Boolean.TRUE, result.get("alreadyFinished"));
        }
    }

    @Nested
    @DisplayName("list_sessions")
    class ListSessions {

        @Test
        @DisplayName("маппит сессию и зовёт сервис с фильтрами")
        void mapsSession() {
            UUID agentId = UUID.randomUUID();
            UUID connectionId = UUID.randomUUID();
            AgentSession session = AgentSession.builder()
                    .id(UUID.randomUUID()).scope(AgentSessionScope.CHANNEL).agentId(agentId)
                    .userId(USER_ID).connectorCode("telegram").connectionId(connectionId)
                    .title("Hello").lastActivityAt(LocalDateTime.of(2025, 5, 5, 5, 5))
                    .build();
            when(agentSessionService.list(eq(USER_ID), isNull(), isNull(), isNull(), eq(0), eq(100)))
                    .thenReturn(new PageImpl<>(List.of(session)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_sessions", Map.of());

            List<?> items = (List<?>) result.get("items");
            assertEquals(1, items.size());
            Map<?, ?> brief = (Map<?, ?>) items.getFirst();
            assertEquals("CHANNEL", brief.get("scope"));
            assertEquals(agentId.toString(), brief.get("agentId"));
            assertEquals("telegram", brief.get("connectorCode"));
            assertEquals(connectionId.toString(), brief.get("connectionId"));
            assertEquals("Hello", brief.get("title"));
            assertEquals("2025-05-05T05:05", brief.get("lastActivityAt"));
            assertNull(brief.get("closedAt"));
        }

        @Test
        @DisplayName("agentId-фильтр пробрасывается в сервис")
        void passesAgentFilter() {
            UUID agentId = UUID.randomUUID();
            when(agentSessionService.list(eq(USER_ID), eq(agentId), isNull(), isNull(), eq(0), eq(100)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(env(), "list_sessions", Map.of("agentId", agentId.toString()));

            verify(agentSessionService).list(eq(USER_ID), eq(agentId), isNull(), isNull(), eq(0), eq(100));
        }
    }

    @Nested
    @DisplayName("get_session")
    class GetSession {

        @Test
        @DisplayName("чужая сессия читается как не найденная")
        void foreignSessionReadsAsNotFound() {
            UUID sessionId = UUID.randomUUID();
            when(agentSessionService.getById(sessionId)).thenReturn(AgentSession.builder()
                    .id(sessionId).scope(AgentSessionScope.CHANNEL)
                    .userId(UUID.randomUUID()).agentId(UUID.randomUUID())
                    .connectorCode("telegram").connectionId(UUID.randomUUID())
                    .build());

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "get_session",
                    Map.of("sessionId", sessionId.toString())));
            assertTrue(ex.getMessage().contains("Session not found"));
        }

        @Test
        @DisplayName("своя сессия возвращается")
        void ownSessionReturned() {
            UUID sessionId = UUID.randomUUID();
            when(agentSessionService.getById(sessionId)).thenReturn(AgentSession.builder()
                    .id(sessionId).scope(AgentSessionScope.CONNECTION).userId(USER_ID)
                    .agentId(UUID.randomUUID()).connectorCode("github").connectionId(UUID.randomUUID())
                    .build());

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_session",
                    Map.of("sessionId", sessionId.toString()));

            assertEquals(sessionId.toString(), result.get("id"));
            assertEquals("CONNECTION", result.get("scope"));
        }
    }

    @Nested
    @DisplayName("cancel_session")
    class CancelSession {

        @Test
        @DisplayName("возвращает число остановленных ранов")
        void returnsCancelledCount() {
            UUID sessionId = UUID.randomUUID();
            when(runCancellationService.cancelSession(sessionId, USER_ID)).thenReturn(2);

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "cancel_session",
                    Map.of("sessionId", sessionId.toString()));

            assertEquals(sessionId.toString(), result.get("sessionId"));
            assertEquals(2, result.get("cancelled"));
        }
    }

    @Nested
    @DisplayName("list_tool_call_logs")
    class ListToolCallLogs {

        @Test
        @DisplayName("статус-фильтр валидируется: мусор — ConnectorException, репозиторий не зовётся")
        void garbageStatusRejected() {
            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(env(),
                    "list_tool_call_logs", Map.of("status", "garbage")));
            assertTrue(ex.getMessage().contains("SUCCESS"));
            assertTrue(ex.getMessage().contains("ERROR"));
            assertTrue(ex.getMessage().contains("PENDING"));
            verifyNoInteractions(toolCallLogRepository);
        }

        @Test
        @DisplayName("accessEffect-фильтр валидируется")
        void garbageAccessEffectRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "list_tool_call_logs",
                    Map.of("accessEffect", "MAYBE")));
            verifyNoInteractions(toolCallLogRepository);
        }

        @Test
        @DisplayName("маппит запись и пробрасывает фильтры; статус выводится из finishAt/error")
        void mapsLogAndPassesFilters() {
            UUID agentId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            ToolCallLog log = ToolCallLog.builder()
                    .id(UUID.randomUUID()).userId(USER_ID).agentId(agentId)
                    .connectorCode("github").connectionId("conn-9").externalId("ext-1")
                    .name("get_issues").accessEffect(AccessEffect.ALLOW).runId(runId)
                    .finishAt(LocalDateTime.of(2025, 1, 1, 10, 0)).output("[]")
                    .build();
            log.setCreatedAt(LocalDateTime.of(2025, 1, 1, 9, 0));
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), eq(agentId), eq("github"), eq("conn-9"),
                    eq(AccessEffect.ALLOW), eq("issue"), eq("SUCCESS"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(log)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs",
                    Map.of("agentId", agentId.toString(), "connectorCode", "github",
                            "connectionId", "conn-9", "name", "issue", "accessEffect", "ALLOW",
                            "status", "SUCCESS"));

            List<?> items = (List<?>) result.get("items");
            assertEquals(1, items.size());
            Map<?, ?> item = (Map<?, ?>) items.getFirst();
            assertEquals(agentId.toString(), item.get("agentId"));
            assertEquals("get_issues", item.get("name"));
            assertEquals("ALLOW", item.get("accessEffect"));
            assertEquals("SUCCESS", item.get("status"));
            assertEquals(runId.toString(), item.get("runId"));
            assertEquals("ext-1", item.get("externalId"));
            assertEquals("2025-01-01T09:00", item.get("createdAt"));
            assertEquals("2025-01-01T10:00", item.get("finishAt"));
            assertEquals("[]", item.get("output"));
        }

        @Test
        @DisplayName("статус выводится по finishAt/error: SUCCESS, ERROR, PENDING")
        void derivesStatus() {
            ToolCallLog success = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("a").externalId("e1")
                    .finishAt(LocalDateTime.now()).build();
            ToolCallLog error = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("b").externalId("e2")
                    .finishAt(LocalDateTime.now()).error("boom").build();
            ToolCallLog pending = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("c").externalId("e3").build();
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(success, error, pending)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs", Map.of());

            List<?> items = (List<?>) result.get("items");
            assertEquals("SUCCESS", ((Map<?, ?>) items.get(0)).get("status"));
            assertEquals("ERROR", ((Map<?, ?>) items.get(1)).get("status"));
            assertEquals("PENDING", ((Map<?, ?>) items.get(2)).get("status"));
        }

        @Test
        @DisplayName("отказанная (DENY) строка без finishAt — ERROR, как и фильтр")
        void refusedRowReadsAsError() {
            ToolCallLog denied = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("x").externalId("e1")
                    .error("denied by policy").build();
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(denied)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs", Map.of());

            assertEquals("ERROR",
                    ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("status"));
        }

        @Test
        @DisplayName("plaintextKey из output create_agent/regenerate не возвращается историей")
        void agentKeyIsRedactedFromOutput() {
            ToolCallLog log = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).connectorCode("platform").name("create_agent")
                    .externalId("e1").finishAt(LocalDateTime.now())
                    .output("{\"id\":\"a1\",\"name\":\"bot\",\"plaintextKey\":\"agnt_secret\"}")
                    .build();
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(log)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs", Map.of());

            String output = (String) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("output");
            assertFalse(output.contains("agnt_secret"));
            assertFalse(output.contains("plaintextKey"));
            assertTrue(output.contains("\"name\":\"bot\""));
        }

        @Test
        @DisplayName("provider-ключ в не-JSON output вырезается паттерном")
        void providerKeyInFreeTextIsRedacted() {
            ToolCallLog log = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("probe").externalId("e1")
                    .finishAt(LocalDateTime.now()).output("used sk-proj-abcdefghijklmnopqrstuvwxyz012345 for the call")
                    .build();
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(log)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs", Map.of());

            String output = (String) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("output");
            assertFalse(output.contains("sk-proj-abcdef"));
            assertTrue(output.contains("[key redacted]"));
        }

        @Test
        @DisplayName("не-JSON output проходит без изменений")
        void nonJsonOutputPassesThrough() {
            ToolCallLog log = ToolCallLog.builder().id(UUID.randomUUID()).userId(USER_ID)
                    .agentId(UUID.randomUUID()).name("search").externalId("e1")
                    .finishAt(LocalDateTime.now()).output("just some text").build();
            when(toolCallLogRepository.findWithFilters(eq(USER_ID), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(log)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_tool_call_logs", Map.of());

            assertEquals("just some text",
                    ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("output"));
        }
    }

    @Nested
    @DisplayName("list_trigger_logs")
    class ListTriggerLogs {

        @Test
        @DisplayName("ключ в поле apiKey внутри входного payload триггера вырезается")
        void triggerInputSecretsAreRedacted() {
            TriggerLogWithAgentsCountProjection trigger = mock(TriggerLogWithAgentsCountProjection.class);
            when(trigger.getId()).thenReturn(UUID.randomUUID());
            when(trigger.getConnectorCode()).thenReturn("webchat");
            when(trigger.getConnectionId()).thenReturn("conn-1");
            when(trigger.getExternalId()).thenReturn("ext");
            when(trigger.getName()).thenReturn("message_received");
            when(trigger.getOccurredAt()).thenReturn(LocalDateTime.of(2025, 1, 1, 9, 0));
            when(trigger.getAgentsCount()).thenReturn(1L);
            when(trigger.getInput()).thenReturn(Map.of(
                    "text", "use sk-proj-abcdefghijklmnopqrstuvwxyz012345 for the call",
                    "apiKey", "AIzaSyD-foreign-style-token"));
            when(triggerLogRepository.findByUserIdWithFilters(eq(USER_ID), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(trigger)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_trigger_logs", Map.of());

            Map<?, ?> input = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("input");
            assertFalse(String.valueOf(input.get("text")).contains("sk-proj-abcdef"));
            assertFalse(input.containsKey("apiKey"));
        }

        @Test
        @DisplayName("маппит проекцию с числом агентов")
        void mapsProjection() {
            TriggerLogWithAgentsCountProjection trigger = mock(TriggerLogWithAgentsCountProjection.class);
            UUID triggerId = UUID.randomUUID();
            when(trigger.getId()).thenReturn(triggerId);
            when(trigger.getConnectorCode()).thenReturn("telegram");
            when(trigger.getConnectionId()).thenReturn("conn-2");
            when(trigger.getExternalId()).thenReturn("ext-9");
            when(trigger.getName()).thenReturn("on_message");
            when(trigger.getOccurredAt()).thenReturn(LocalDateTime.of(2025, 2, 2, 2, 2));
            when(trigger.getAgentsCount()).thenReturn(2L);
            when(trigger.getInput()).thenReturn(Map.of("text", "hi"));
            when(triggerLogRepository.findByUserIdWithFilters(eq(USER_ID), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(trigger)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_trigger_logs", Map.of());

            List<?> items = (List<?>) result.get("items");
            assertEquals(1, items.size());
            Map<?, ?> item = (Map<?, ?>) items.getFirst();
            assertEquals(triggerId.toString(), item.get("id"));
            assertEquals("telegram", item.get("connectorCode"));
            assertEquals("conn-2", item.get("connectionId"));
            assertEquals("ext-9", item.get("externalId"));
            assertEquals("on_message", item.get("name"));
            assertEquals("2025-02-02T02:02", item.get("occurredAt"));
            assertEquals(2L, ((Number) item.get("agentsCount")).longValue());
            assertEquals(Map.of("text", "hi"), item.get("input"));
        }

        @Test
        @DisplayName("connectorCode-фильтр пробрасывается")
        void passesConnectorFilter() {
            when(triggerLogRepository.findByUserIdWithFilters(eq(USER_ID), eq("telegram"),
                    any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(env(), "list_trigger_logs", Map.of("connectorCode", "telegram"));

            verify(triggerLogRepository).findByUserIdWithFilters(eq(USER_ID), eq("telegram"),
                    any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("list_webhook_deliveries")
    class ListWebhookDeliveries {

        @Test
        @DisplayName("маппит доставку; без фильтра — findByUserId")
        void mapsDeliveryWithoutFilter() {
            UUID runId = UUID.randomUUID();
            AgentRun run = mock(AgentRun.class);
            when(run.getId()).thenReturn(runId);
            WebhookDeliveryLog delivery = WebhookDeliveryLog.builder()
                    .id(UUID.randomUUID()).agentRun(run).requestUrl("https://example.com/hook")
                    .responseStatusCode(200).error(null).durationMs(42L)
                    .deliveredAt(LocalDateTime.of(2025, 3, 3, 3, 3))
                    .build();
            when(webhookDeliveryLogRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(delivery)));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "list_webhook_deliveries", Map.of());

            List<?> items = (List<?>) result.get("items");
            assertEquals(1, items.size());
            Map<?, ?> item = (Map<?, ?>) items.getFirst();
            assertEquals(runId.toString(), item.get("runId"));
            assertEquals("https://example.com/hook", item.get("requestUrl"));
            assertEquals(200, item.get("responseStatusCode"));
            assertEquals(42L, item.get("durationMs"));
            assertEquals("2025-03-03T03:03", item.get("deliveredAt"));
            assertEquals(Boolean.TRUE, item.get("success"));
            assertNull(item.get("error"));
        }

        @Test
        @DisplayName("запрос несёт явную сортировку «newest first»")
        void sortsByDeliveredAtDesc() {
            when(webhookDeliveryLogRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(env(), "list_webhook_deliveries", Map.of());

            verify(webhookDeliveryLogRepository).findByUserId(eq(USER_ID), argThat(pageable ->
                    pageable.getSort().getOrderFor("deliveredAt") != null
                            && pageable.getSort().getOrderFor("deliveredAt").isDescending()));
        }

        @Test
        @DisplayName("пустой agentId-фильтр — то же, что без фильтра")
        void blankAgentFilterTreatedAsNone() {
            when(webhookDeliveryLogRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(env(), "list_webhook_deliveries", Map.of("agentId", ""));

            verify(webhookDeliveryLogRepository).findByUserId(eq(USER_ID), any(Pageable.class));
            verify(webhookDeliveryLogRepository, never()).findByUserIdAndAgentId(any(), any(), any());
        }

        @Test
        @DisplayName("agentId-фильтр выбирает findByUserIdAndAgentId")
        void passesAgentFilter() {
            UUID agentId = UUID.randomUUID();
            when(webhookDeliveryLogRepository.findByUserIdAndAgentId(eq(USER_ID), eq(agentId),
                    any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(env(), "list_webhook_deliveries", Map.of("agentId", agentId.toString()));

            verify(webhookDeliveryLogRepository).findByUserIdAndAgentId(eq(USER_ID), eq(agentId),
                    any(Pageable.class));
            verify(webhookDeliveryLogRepository, never()).findByUserId(any(), any());
        }
    }

    @Nested
    @DisplayName("get_run_turns")
    class GetRunTurns {

        @Test
        @DisplayName("маппит ходы в порядке ledger'а, сплющивая tool-колонки")
        void mapsTurnsOldestFirst() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));

            AgentRunTurn user = AgentRunTurn.builder().runId(runId).turnIndex(0)
                    .role(AgentTurnRole.USER).text("hello").build();
            user.setCreatedAt(LocalDateTime.of(2025, 4, 4, 4, 0));
            AgentRunTurn assistant = AgentRunTurn.builder().runId(runId).turnIndex(1)
                    .role(AgentTurnRole.ASSISTANT).text("let me check")
                    .toolCalls(List.of(Map.of("id", "c1", "name", "search", "argumentsJson", "{\"q\":\"x\"}")))
                    .build();
            assistant.setCreatedAt(LocalDateTime.of(2025, 4, 4, 4, 1));
            AgentRunTurn toolOk = AgentRunTurn.builder().runId(runId).turnIndex(2)
                    .role(AgentTurnRole.TOOL)
                    .toolResults(List.of(Map.of("id", "r1", "name", "search",
                            "outputJson", "{\"n\":1}", "failed", false)))
                    .build();
            toolOk.setCreatedAt(LocalDateTime.of(2025, 4, 4, 4, 2));
            AgentRunTurn toolFailed = AgentRunTurn.builder().runId(runId).turnIndex(3)
                    .role(AgentTurnRole.TOOL)
                    .toolResults(List.of(Map.of("id", "r2", "name", "send",
                            "outputJson", "{\"error\":\"no channel\"}", "failed", true)))
                    .build();
            toolFailed.setCreatedAt(LocalDateTime.of(2025, 4, 4, 4, 3));
            when(agentRunTurnRepository.findByRunIdOrderByTurnIndexAsc(runId))
                    .thenReturn(List.of(user, assistant, toolOk, toolFailed));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_turns",
                    Map.of("runId", runId.toString()));

            List<?> items = (List<?>) result.get("items");
            assertEquals(4, items.size());

            Map<?, ?> first = (Map<?, ?>) items.get(0);
            assertEquals(0, first.get("turnIndex"));
            assertEquals("USER", first.get("role"));
            assertEquals("hello", first.get("content"));
            assertNull(first.get("toolName"));

            Map<?, ?> second = (Map<?, ?>) items.get(1);
            assertEquals("ASSISTANT", second.get("role"));
            assertEquals("search", second.get("toolName"));
            assertEquals("{\"q\":\"x\"}", second.get("toolInput"));
            // The full call list rides along, not just the flattened first call.
            List<?> calls = (List<?>) second.get("toolCalls");
            assertEquals(1, calls.size());
            assertEquals("c1", ((Map<?, ?>) calls.getFirst()).get("id"));
            assertEquals("{\"q\":\"x\"}", ((Map<?, ?>) calls.getFirst()).get("argumentsJson"));
            assertNull(second.get("toolResults"));

            Map<?, ?> third = (Map<?, ?>) items.get(2);
            assertEquals("TOOL", third.get("role"));
            assertEquals("search", third.get("toolName"));
            assertEquals("{\"n\":1}", third.get("toolOutput"));
            assertNull(third.get("toolError"));
            assertEquals("2025-04-04T04:02", third.get("createdAt"));
            List<?> results = (List<?>) third.get("toolResults");
            assertEquals(1, results.size());
            assertEquals("{\"n\":1}", ((Map<?, ?>) results.getFirst()).get("outputJson"));

            Map<?, ?> fourth = (Map<?, ?>) items.get(3);
            assertEquals("send", fourth.get("toolName"));
            assertNull(fourth.get("toolOutput"));
            assertEquals("{\"error\":\"no channel\"}", fourth.get("toolError"));
        }

        @Test
        @DisplayName("ключ, процитированный моделью в тексте хода, вырезается")
        void keysQuotedInTurnTextAreRedacted() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            AgentRunTurn assistant = AgentRunTurn.builder().runId(runId).turnIndex(0)
                    .role(AgentTurnRole.ASSISTANT)
                    .text("created, the key is agntapLrNHYBw8f3QtfDE9ueFPWbejAPskkSl21TwPYo9PHII1Oc6UfK_DD4CSDl keep it safe")
                    .build();
            when(agentRunTurnRepository.findByRunIdOrderByTurnIndexAsc(runId))
                    .thenReturn(List.of(assistant));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_turns",
                    Map.of("runId", runId.toString()));

            String content = (String) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("content");
            assertFalse(content.contains("agntapLr"));
            assertTrue(content.contains("[key redacted]"));
        }

        @Test
        @DisplayName("ключ с хвостовым '-' (base64url) вырезается — lookaround вместо \b")
        void keyEndingInDashIsRedacted() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            // 4-prefix + 59 alnum + '-' = a valid 64-char key whose last char is not a word char:
            // \b would miss the boundary and let the whole key through.
            String key = "agnt" + "A".repeat(59) + "-";
            AgentRunTurn assistant = AgentRunTurn.builder().runId(runId).turnIndex(0)
                    .role(AgentTurnRole.ASSISTANT).text("key is " + key + " end").build();
            when(agentRunTurnRepository.findByRunIdOrderByTurnIndexAsc(runId))
                    .thenReturn(List.of(assistant));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_turns",
                    Map.of("runId", runId.toString()));

            String content = (String) ((Map<?, ?>) ((List<?>) result.get("items")).getFirst()).get("content");
            assertFalse(content.contains(key));
            assertTrue(content.contains("[key redacted]"));
        }

        @Test
        @DisplayName("чужой ран — ConnectorException, ходы не читаются")
        void foreignRunReadsAsNotFound() {
            UUID runId = UUID.randomUUID();
            when(agentRunRepository.findRunsWithFilters(eq(USER_ID), eq(runId), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "get_run_turns",
                    Map.of("runId", runId.toString())));
            assertTrue(ex.getMessage().contains("Run not found: " + runId));
            verifyNoInteractions(agentRunTurnRepository);
        }

        @Test
        @DisplayName("секреты в аргументах и результатах тулов вырезаются из транскрипта")
        void secretsAreRedactedFromTurns() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));

            AgentRunTurn assistant = AgentRunTurn.builder().runId(runId).turnIndex(0)
                    .role(AgentTurnRole.ASSISTANT)
                    .toolCalls(List.of(Map.of("id", "c1", "name", "create_llm_provider",
                            "argumentsJson", "{\"name\":\"openai\",\"apiKey\":\"sk-topsecret\"}")))
                    .build();
            AgentRunTurn toolOk = AgentRunTurn.builder().runId(runId).turnIndex(1)
                    .role(AgentTurnRole.TOOL)
                    .toolResults(List.of(Map.of("id", "r1", "name", "create_agent",
                            "outputJson", "{\"id\":\"a1\",\"plaintextKey\":\"agnt_secret\"}", "failed", false)))
                    .build();
            when(agentRunTurnRepository.findByRunIdOrderByTurnIndexAsc(runId))
                    .thenReturn(List.of(assistant, toolOk));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_turns",
                    Map.of("runId", runId.toString()));

            List<?> items = (List<?>) result.get("items");
            String input = (String) ((Map<?, ?>) items.get(0)).get("toolInput");
            assertFalse(input.contains("sk-topsecret"));
            assertFalse(input.contains("apiKey"));
            assertTrue(input.contains("\"name\":\"openai\""));
            String output = (String) ((Map<?, ?>) items.get(1)).get("toolOutput");
            assertFalse(output.contains("agnt_secret"));
            assertFalse(output.contains("plaintextKey"));
            assertTrue(output.contains("\"id\":\"a1\""));
            // The full lists are redacted the same way.
            List<?> calls = (List<?>) ((Map<?, ?>) items.get(0)).get("toolCalls");
            String listInput = (String) ((Map<?, ?>) calls.getFirst()).get("argumentsJson");
            assertFalse(listInput.contains("sk-topsecret"));
            assertTrue(listInput.contains("\"name\":\"openai\""));
            List<?> results = (List<?>) ((Map<?, ?>) items.get(1)).get("toolResults");
            String listOutput = (String) ((Map<?, ?>) results.getFirst()).get("outputJson");
            assertFalse(listOutput.contains("agnt_secret"));
            assertTrue(listOutput.contains("\"id\":\"a1\""));
        }
    }

    @Nested
    @DisplayName("get_run_prompt")
    class GetRunPrompt {

        @Test
        @DisplayName("возвращает промпт-карту из entity (owner check через проекцию)")
        void returnsPromptMap() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            Map<String, Object> prompt = Map.of("messages",
                    List.of(Map.of("role", "user", "content", "hi")));
            AgentRun entity = AgentRun.builder().id(runId)
                    .prompt(JsonUtils.MAPPER.valueToTree(prompt)).build();
            when(agentRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_prompt",
                    Map.of("runId", runId.toString()));

            assertEquals(runId.toString(), result.get("runId"));
            assertEquals(prompt, result.get("prompt"));
        }

        @Test
        @DisplayName("ключ, процитированный в истории сессии внутри промпта, вырезается")
        void keysInPromptHistoryAreRedacted() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            JsonNode array = JsonUtils.MAPPER.valueToTree(List.of(Map.of(
                    "role", "assistant",
                    "content", "created, the key is agntapLrNHYBw8f3QtfDE9ueFPWbejAPskkSl21TwPYo9PHII1Oc6UfK_DD4CSDl keep it")));
            AgentRun entity = AgentRun.builder().id(runId).prompt(array).build();
            when(agentRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_prompt",
                    Map.of("runId", runId.toString()));

            Map<?, ?> prompt = (Map<?, ?>) result.get("prompt");
            String content = (String) ((Map<?, ?>) ((List<?>) prompt.get("messages")).getFirst())
                    .get("content");
            assertFalse(content.contains("agntapLr"));
            assertTrue(content.contains("[key redacted]"));
        }

        @Test
        @DisplayName("промпт-массив (реальная форма) оборачивается в messages")
        void wrapsArrayPrompt() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            JsonNode array = JsonUtils.MAPPER.valueToTree(List.of(Map.of("role", "user", "content", "hi")));
            AgentRun entity = AgentRun.builder().id(runId).prompt(array).build();
            when(agentRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_prompt",
                    Map.of("runId", runId.toString()));

            Map<?, ?> prompt = (Map<?, ?>) result.get("prompt");
            assertEquals(List.of(Map.of("role", "user", "content", "hi")), prompt.get("messages"));
        }

        @Test
        @DisplayName("null-промпт — пустая карта")
        void nullPromptIsEmptyMap() {
            UUID runId = UUID.randomUUID();
            stubOwnedRun(agentRunRepository, runId, runProjection(runId, UUID.randomUUID()));
            AgentRun entity = AgentRun.builder().id(runId).prompt(null).build();
            when(agentRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(env(), "get_run_prompt",
                    Map.of("runId", runId.toString()));

            assertEquals(Map.of(), result.get("prompt"));
        }
    }
}
