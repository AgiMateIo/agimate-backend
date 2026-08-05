package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TriggerRouterService — кто получает триггер")
class TriggerRouterServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();

    @Mock
    private TriggerLogService triggerLogService;
    @Mock
    private TriggerLogProbeService triggerLogProbeService;
    @Mock
    private ConnectionAccessEvaluator accessEvaluator;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentDeliveryService agentDeliveryService;
    @Mock
    private ChannelRouteResolver channelRouteResolver;
    @Mock
    private AgentRunRepository agentRunRepository;

    @InjectMocks
    private TriggerRouterService routerService;

    private final Trigger trigger = Trigger.fromSource(
            "telegram", CONNECTION.toString(), "message", "t1", Map.of(), Instant.now());

    @BeforeEach
    void setUp() {
        when(triggerLogService.createTriggerLog(eq(USER), any())).thenReturn(TriggerLog.builder()
                .connectorCode("telegram")
                .name("message")
                .build());
        when(triggerLogProbeService.isBlockProbe(any())).thenReturn(false);
        when(accessEvaluator.evaluate(any(UUID.class), any(UUID.class), eq(PolicyKind.TRIGGER), any()))
                .thenReturn(AccessDecision.allow(null));
        when(channelRouteResolver.resolve(any(), any()))
                .thenAnswer(invocation -> ChannelResolution.direct());
        when(agentRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Agent agent(AgentType type) {
        return Agent.builder().id(UUID.randomUUID()).userId(USER).name(type.name()).type(type).build();
    }

    @Test
    @DisplayName("MCP-агент привязан к коннекшену, но триггер ему не создаёт ран")
    void mcpAgentIsNotARecipient() {
        Agent mcp = agent(AgentType.MCP);
        when(agentRepository.findBoundToConnection(USER, CONNECTION)).thenReturn(List.of(mcp));
        when(agentDeliveryService.supportsPush(mcp)).thenReturn(false);

        routerService.routeTrigger(USER, trigger);

        verify(agentRunRepository, never()).save(any());
        verify(agentDeliveryService, never()).deliverTrigger(any(), any(), any(), any());
    }

    @Test
    @DisplayName("агент с push-транспортом получает ран и доставку")
    void pushableAgentIsARecipient() {
        Agent centrifugo = agent(AgentType.CENTRIFUGO);
        when(agentRepository.findBoundToConnection(USER, CONNECTION)).thenReturn(List.of(centrifugo));
        when(agentDeliveryService.supportsPush(centrifugo)).thenReturn(true);

        routerService.routeTrigger(USER, trigger);

        verify(agentRunRepository).save(any(AgentRun.class));
        verify(agentDeliveryService).deliverTrigger(any(), eq(trigger), any(), any());
    }
}
