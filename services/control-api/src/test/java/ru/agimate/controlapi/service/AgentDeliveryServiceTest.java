package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.delivery.AgentTransport;
import ru.agimate.controlapi.service.delivery.DetachedToolResultDelivery;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentDeliveryService.deliverToolResult — развилка «детач или транспорт»")
class AgentDeliveryServiceTest {

    private final AgentTransport transport = mock(AgentTransport.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
    private final DetachedToolResultDelivery detachedDelivery = mock(DetachedToolResultDelivery.class);
    private final Agent agent = mock(Agent.class);

    private final UUID agentId = UUID.randomUUID();
    private final UUID logId = UUID.randomUUID();
    private final ToolResult result = new ToolResult("call-1", "sheets", "{}", null);

    private AgentDeliveryService service;

    @BeforeEach
    void setUp() {
        when(transport.getAgentType()).thenReturn(AgentType.GENERIC);
        service = new AgentDeliveryService(List.of(transport), agentRepository,
                toolCallLogRepository, detachedDelivery);
        lenient().when(agent.getType()).thenReturn(AgentType.GENERIC);
        lenient().when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
    }

    private ToolCallLog log(LocalDateTime detachedAt) {
        ToolCallLog log = ToolCallLog.builder()
                .id(logId)
                .agentId(agentId)
                .externalId("call-1")
                .name("generate_report")
                .connectorCode("sheets")
                .detachedAt(detachedAt)
                .build();
        lenient().when(toolCallLogRepository.findById(logId)).thenReturn(Optional.of(log));
        return log;
    }

    @Test
    @DisplayName("не-детачнутый вызов идёт в транспорт по типу агента, как раньше")
    void plainResultGoesToTransport() {
        service.deliverToolResult(log(null), result);

        verify(transport).deliverToolResult(agent, result);
        verify(detachedDelivery, never()).prepare(any(), any());
    }

    @Test
    @DisplayName("детачнутый вызов минует транспорт: подготовка доставки + deliverTrigger")
    void detachedResultBecomesTriggerRun() {
        when(transport.supportsPush()).thenReturn(true);
        AgentRun run = AgentRun.builder().agent(agent)
                .triggerLog(TriggerLog.builder().name("tool_completed").build()).build();
        Trigger trigger = Trigger.createBasic("sheets", "conn-1", "tool_completed", Map.of());
        ToolCallLog toolCallLog = log(LocalDateTime.now());
        when(detachedDelivery.prepare(toolCallLog, result))
                .thenReturn(Optional.of(new DetachedToolResultDelivery.Prepared(run, trigger, null)));

        service.deliverToolResult(toolCallLog, result);

        verify(transport, never()).deliverToolResult(any(), any());
        verify(transport).deliverTrigger(eq(run), eq(trigger), isNull(), isNull());
    }

    @Test
    @DisplayName("агент без push-транспорта: результат остаётся в логе, ран не создаётся")
    void noPushTransportKeepsResultInLog() {
        when(transport.supportsPush()).thenReturn(false);

        service.deliverToolResult(log(LocalDateTime.now()), result);

        verify(detachedDelivery, never()).prepare(any(), any());
        verify(transport, never()).deliverTrigger(any(), any(), any(), any());
    }

    @Test
    @DisplayName("подготовка отказала (дубль, отмена) — доставки нет и это не ошибка")
    void emptyPreparationMeansNoDelivery() {
        when(transport.supportsPush()).thenReturn(true);
        when(detachedDelivery.prepare(any(), any())).thenReturn(Optional.empty());

        service.deliverToolResult(log(LocalDateTime.now()), result);

        verify(transport, never()).deliverTrigger(any(), any(), any(), any());
    }

    @Test
    @DisplayName("детач лёг в БД посреди исполнения: протухшая сущность вызывающего не прячет его")
    void staleEntityDoesNotHideDetach() {
        // executeTool держит строку, созданную до старта исполнения; воркер детачит вызов, пока
        // тул работает. Развилка обязана читать текущую строку, а не снапшот вызывающего.
        ToolCallLog stale = ToolCallLog.builder()
                .id(logId)
                .agentId(agentId)
                .externalId("call-1")
                .connectorCode("sheets")
                .build();
        ToolCallLog fresh = log(LocalDateTime.now());
        when(transport.supportsPush()).thenReturn(true);
        when(detachedDelivery.prepare(fresh, result)).thenReturn(Optional.empty());

        service.deliverToolResult(stale, result);

        verify(transport, never()).deliverToolResult(any(), any());
        verify(detachedDelivery).prepare(fresh, result);
    }
}
