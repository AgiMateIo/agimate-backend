package ru.agimate.controlapi.service.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService.WaitOutcome;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.enums.ToolCallInitiator;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.ConnectorService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.tool.AgentToolCallService.CallOutcome;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolCallService")
class AgentToolCallServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String EXTERNAL_ID = "aB3xY9k2Q";

    @Mock
    private AgentService agentService;
    @Mock
    private ToolCallLogService toolCallLogService;
    @Mock
    private ConnectionAccessEvaluator accessEvaluator;
    @Mock
    private ConnectorService connectorService;
    @Mock
    private ToolExecutionService toolExecutionService;

    @InjectMocks
    private AgentToolCallService service;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId(AGENT_ID);
        when(agentService.findById(AGENT_ID)).thenReturn(agent);
    }

    private void existingLog(String name, Map<String, Object> input) {
        ToolCallLog log = ToolCallLog.builder()
                .agentId(AGENT_ID)
                .externalId(EXTERNAL_ID)
                .name(name)
                .input(input)
                .accessEffect(AccessEffect.ALLOW)
                .build();
        when(toolCallLogService.findByExternalIdAndAgentId(EXTERNAL_ID, AGENT_ID))
                .thenReturn(Optional.of(log));
    }

    private static ToolCallRequest request(String name, Map<String, Object> input) {
        return ToolCallRequest.builder()
                .id(EXTERNAL_ID)
                .connectorCode("board")
                .connectionId(UUID.randomUUID().toString())
                .name(name)
                .input(input)
                .build();
    }

    @Nested
    @DisplayName("тот же вызов — replay")
    class SameCall {

        @Test
        @DisplayName("то же имя и тот же вход — replay, ABAC заново не считается")
        void sameNameAndInputReplays() {
            existingLog("get_tasks", Map.of());

            assertEquals(AccessEffect.ALLOW, service.checkToolCall(AGENT_ID, request("get_tasks", Map.of())));
        }
    }

    @Nested
    @DisplayName("другой вызов под тем же id — конфликт")
    class DifferentCall {

        @Test
        @DisplayName("другой вход — конфликт")
        void differentInputConflicts() {
            existingLog("create_task", Map.of("title", "a"));

            assertThrows(ConflictStatusException.class,
                    () -> service.checkToolCall(AGENT_ID, request("create_task", Map.of("title", "b"))));
        }

        @Test
        @DisplayName("тот же вход, но другой тул — конфликт, а не чужой результат")
        void differentToolWithTheSameInputConflicts() {
            // Both take no arguments, so the input alone cannot tell the two calls apart — the case
            // an id collision would otherwise resolve by handing over the wrong tool's result.
            existingLog("get_tasks", Map.of());

            assertThrows(ConflictStatusException.class,
                    () -> service.checkToolCall(AGENT_ID, request("current_datetime", Map.of())));
        }
    }
    @Nested
    @DisplayName("вызов на месте — callAsAgent")
    class CallAsAgent {

        private static final UUID CONNECTION_ID = UUID.randomUUID();
        private final ToolCallLog log = ToolCallLog.builder().agentId(AGENT_ID).externalId("x").build();

        private CallOutcome call() {
            return service.callAsAgent(AGENT_ID, "mcp", CONNECTION_ID, "show", Map.of(), Duration.ofSeconds(5),
                    ToolCallInitiator.VIEW);
        }

        private void decision(AccessDecision decision) {
            when(accessEvaluator.evaluate(eq(AGENT_ID), eq(CONNECTION_ID.toString()), any(), eq("show")))
                    .thenReturn(decision);
            when(toolCallLogService.createLog(any(), any(), any(), any(), any(), any(), eq(ToolCallInitiator.VIEW)))
                    .thenReturn(log);
        }

        @Test
        @DisplayName("отказ правил — Refused, тул не запускается")
        void refused() {
            decision(AccessDecision.deny("denied"));

            assertInstanceOf(CallOutcome.Refused.class, call());
            verify(toolExecutionService, never()).executeWithTimeout(any(), any());
        }

        @Test
        @DisplayName("результат в пределах ожидания — Completed; лог пишется с инициатором вызывающего")
        void completed() {
            decision(AccessDecision.allow(null));
            ToolResult result = new ToolResult("x", "mcp", "{}", null);
            when(toolExecutionService.executeWithTimeout(log, Duration.ofSeconds(5)))
                    .thenReturn(new WaitOutcome.Completed(result));

            assertEquals(new CallOutcome.Completed(result), call());
        }

        @Test
        @DisplayName("ожидание вышло — StillRunning с логом, по которому вызов можно отцепить")
        void stillRunning() {
            decision(AccessDecision.allow(null));
            when(toolExecutionService.executeWithTimeout(log, Duration.ofSeconds(5)))
                    .thenReturn(new WaitOutcome.StillRunning());

            assertEquals(new CallOutcome.StillRunning(log), call());
        }
    }
}
