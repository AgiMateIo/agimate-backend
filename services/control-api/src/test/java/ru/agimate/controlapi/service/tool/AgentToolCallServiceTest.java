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
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.ConnectorService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolCallService — повтор того же tool_call_id")
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
}
