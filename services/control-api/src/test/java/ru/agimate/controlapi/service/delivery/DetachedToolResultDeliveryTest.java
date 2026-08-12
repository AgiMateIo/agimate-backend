package ru.agimate.controlapi.service.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerLogService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DetachedToolResultDelivery — детачнутый результат в ран tool_completed")
class DetachedToolResultDeliveryTest {

    private final ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final TriggerLogService triggerLogService = mock(TriggerLogService.class);

    private final DetachedToolResultDelivery delivery = new DetachedToolResultDelivery(
            toolCallLogRepository, agentRunRepository, triggerLogService);

    private final UUID logId = UUID.randomUUID();
    private final UUID parentRunId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    private final Agent agent = mock(Agent.class);
    private AgentRun parent;

    @BeforeEach
    void setUp() {
        lenient().when(agent.getType()).thenReturn(AgentType.GENERIC);
        parent = AgentRun.builder()
                .agent(agent)
                .sessionId(sessionId)
                .channels(ChannelsCodec.toMap(new Channels(
                        new ChannelInfo(channelId, sessionId, null),
                        new ChannelInfo(channelId, sessionId, null),
                        null)))
                .build();
        lenient().when(agentRunRepository.findById(parentRunId)).thenReturn(Optional.of(parent));
        lenient().when(toolCallLogRepository.claimDelivery(eq(logId), any())).thenReturn(1);
        lenient().when(triggerLogService.createTriggerLog(eq(userId), any(Trigger.class)))
                .thenReturn(TriggerLog.builder().name(DetachedToolResultDelivery.TRIGGER_NAME).build());
        lenient().when(agentRunRepository.save(any(AgentRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ToolCallLog detachedLog() {
        return ToolCallLog.builder()
                .id(logId)
                .userId(userId)
                .agentId(UUID.randomUUID())
                .runId(parentRunId)
                .externalId("call-1")
                .name("generate_report")
                .connectorCode("sheets")
                .connectionId("conn-1")
                .detachedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("доставка: ран в сессии родителя, каналы без prompt, prompt повышен до answer")
    void createsRunInParentSession() {
        Optional<DetachedToolResultDelivery.Prepared> prepared =
                delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", "{\"ok\":1}", null));

        assertTrue(prepared.isPresent());
        AgentRun run = prepared.get().run();
        assertEquals(sessionId, run.getSessionId());
        assertEquals(AgentType.GENERIC.name(), run.getDestination());
        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        assertNull(channels.prompt());
        assertEquals(channelId, channels.answer().channelId());
        assertEquals(channelId, channels.progress().channelId());
    }

    @Test
    @DisplayName("триггер tool_completed несёт task_id и результат, коррелируется по id лога")
    void triggerCarriesTaskIdAndOutput() {
        delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", "{\"ok\":1}", null));

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerLogService).createTriggerLog(eq(userId), captor.capture());
        Trigger trigger = captor.getValue();
        assertEquals(DetachedToolResultDelivery.TRIGGER_NAME, trigger.name());
        assertEquals("sheets", trigger.connectorCode());
        assertEquals(logId.toString(), trigger.id());
        assertEquals("call-1", trigger.data().get("task_id"));
        assertEquals("success", trigger.data().get("status"));
        assertEquals("{\"ok\":1}", trigger.data().get("output"));
    }

    @Test
    @DisplayName("ошибка тула доставляется так же, со статусом error")
    void errorOutcomeIsDeliveredToo() {
        delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", null, "boom"));

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerLogService).createTriggerLog(eq(userId), captor.capture());
        assertEquals("error", captor.getValue().data().get("status"));
        assertEquals("boom", captor.getValue().data().get("error"));
    }

    @Test
    @DisplayName("claim проигран (двойной пост результата) — второго рана нет")
    void duplicateCompletionIsIgnored() {
        when(toolCallLogRepository.claimDelivery(eq(logId), any())).thenReturn(0);

        assertTrue(delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", "{}", null))
                .isEmpty());
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("отменённый ран-родитель гасит доставку до claim'а")
    void cancelledParentSuppressesDelivery() {
        parent.setCancelRequestedAt(LocalDateTime.now());

        assertTrue(delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", "{}", null))
                .isEmpty());
        verify(toolCallLogRepository, never()).claimDelivery(any(), any());
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("direct-родитель без каналов — direct-доставка, каналы null")
    void directParentMeansDirectDelivery() {
        parent = AgentRun.builder().agent(agent).build();
        when(agentRunRepository.findById(parentRunId)).thenReturn(Optional.of(parent));

        Optional<DetachedToolResultDelivery.Prepared> prepared =
                delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", "{}", null));

        assertTrue(prepared.isPresent());
        assertNull(prepared.get().channels());
        assertNull(prepared.get().run().getSessionId());
    }

    @Test
    @DisplayName("вызов вне рана — доставлять некуда")
    void callOutsideRunIsNotDeliverable() {
        ToolCallLog log = detachedLog();
        log.setRunId(null);

        assertTrue(delivery.prepare(log, new ToolResult("call-1", "sheets", "{}", null)).isEmpty());
        verify(toolCallLogRepository, never()).claimDelivery(any(), any());
    }

    @Test
    @DisplayName("гигантский вывод обрезается с явной пометкой")
    void hugeOutputIsCapped() {
        String huge = "x".repeat(DetachedToolResultDelivery.OUTPUT_CAP + 5_000);
        delivery.prepare(detachedLog(), new ToolResult("call-1", "sheets", huge, null));

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerLogService).createTriggerLog(eq(userId), captor.capture());
        String output = (String) captor.getValue().data().get("output");
        assertTrue(output.length() < huge.length());
        assertTrue(output.contains("[truncated: " + huge.length() + " chars total"));
    }
}
