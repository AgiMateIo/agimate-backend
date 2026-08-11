package ru.agimate.controlapi.service.trigger;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private RunCancellationService runCancellationService;
    @Mock
    private ru.agimate.controlapi.service.channel.ChannelMessageOutboundService outboundService;
    @Mock
    private ru.agimate.controlapi.service.seed.ChannelTexts channelTexts;

    @InjectMocks
    private TriggerRouterService routerService;

    private final Trigger trigger = Trigger.fromSource(
            "telegram", CONNECTION.toString(), "message", "t1", Map.of(), Instant.now());

    @BeforeEach
    void setUp() {
        TriggerLog triggerLog = TriggerLog.builder()
                .userId(USER)
                .connectorCode("telegram")
                .name("message")
                .build();
        triggerLog.setId(UUID.randomUUID());
        when(triggerLogService.createTriggerLog(eq(USER), any())).thenReturn(triggerLog);
        when(triggerLogProbeService.isBlockProbe(any())).thenReturn(false);
        when(accessEvaluator.evaluate(any(UUID.class), any(UUID.class), eq(PolicyKind.TRIGGER), any()))
                .thenReturn(AccessDecision.allow(null));
        when(channelRouteResolver.resolve(any(), any()))
                .thenAnswer(invocation -> ChannelResolution.direct());
        when(agentRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(channelTexts.get(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    /** Маршрут «это стоп-команда», как его вернул бы резолвер. */
    private void stubCancelRoute(UUID sessionId) {
        UUID channelId = UUID.randomUUID();
        when(channelRouteResolver.resolve(any(), any())).thenAnswer(invocation ->
                ChannelResolution.cancel(Channels.ofPrompt(new ChannelInfo(channelId, sessionId, null))));
    }

    private Agent boundGenericAgent() {
        Agent agent = agent(AgentType.GENERIC);
        when(agentRepository.findBoundToConnection(USER, CONNECTION)).thenReturn(List.of(agent));
        when(agentDeliveryService.supportsPush(agent)).thenReturn(true);
        return agent;
    }

    private Agent agent(AgentType type) {
        return Agent.builder().id(UUID.randomUUID()).userId(USER).name(type.name()).type(type).build();
    }

    @Nested
    @DisplayName("стоп-команда")
    class StopCommand {

        @Test
        @DisplayName("рана не создаётся вовсе — команда адресована платформе, не агенту")
        void cancelCreatesNoRun() {
            UUID sessionId = UUID.randomUUID();
            boundGenericAgent();
            stubCancelRoute(sessionId);
            when(runCancellationService.cancelSessionFromChannel(sessionId)).thenReturn(1);

            routerService.routeTrigger(USER, trigger);

            verify(agentRunRepository, never()).save(any());
            verify(agentDeliveryService, never()).deliverTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("что-то остановили → в чат ничего не пишем, ответом будет «Остановлено…» самого рана")
        void silentWhenSomethingWasStopped() {
            UUID sessionId = UUID.randomUUID();
            boundGenericAgent();
            stubCancelRoute(sessionId);
            when(runCancellationService.cancelSessionFromChannel(sessionId)).thenReturn(2);

            routerService.routeTrigger(USER, trigger);

            verifyNoInteractions(outboundService);
        }

        @Test
        @DisplayName("останавливать было нечего → служебный ответ в канал")
        void repliesWhenNothingWasStopped() {
            UUID sessionId = UUID.randomUUID();
            boundGenericAgent();
            stubCancelRoute(sessionId);
            when(runCancellationService.cancelSessionFromChannel(sessionId)).thenReturn(0);

            routerService.routeTrigger(USER, trigger);

            verify(outboundService).send(any(), any(), eq(sessionId), any(), any(), eq("answer"), isNull());
        }

        @Test
        @DisplayName("живой сессии нет → отменять нечего, сразу служебный ответ")
        void repliesWithoutSession() {
            boundGenericAgent();
            stubCancelRoute(null);

            routerService.routeTrigger(USER, trigger);

            verify(runCancellationService, never()).cancelSessionFromChannel(any());
            verify(outboundService).send(any(), any(), isNull(), any(), any(), eq("answer"), isNull());
        }
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
