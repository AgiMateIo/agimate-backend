package ru.agimate.controlapi.service.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubagentReportDelivery")
class SubagentReportDeliveryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHILD_RUN = UUID.randomUUID();
    private static final UUID CHILD_SESSION = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID CHAT_CHANNEL = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private TriggerLogService triggerLogService;
    @Mock private SubagentService subagentService;

    private SubagentReportDelivery delivery;
    private AgentRun child;

    @BeforeEach
    void setUp() {
        delivery = new SubagentReportDelivery(agentRunRepository, agentSessionRepository, triggerLogService,
                subagentService);
        child = AgentRun.builder()
                .agent(Agent.builder().id(UUID.randomUUID()).type(AgentType.GENERIC).build())
                .sessionId(CHILD_SESSION)
                .status(RunStatus.DONE)
                .build();
        child.setId(CHILD_RUN);
        lenient().when(agentRunRepository.findById(CHILD_RUN)).thenReturn(Optional.of(child));
        lenient().when(agentRunRepository.save(any(AgentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(triggerLogService.createTriggerLog(eq(USER_ID), any())).thenReturn(TriggerLog.builder().build());
    }

    private void childSession(UUID parent) {
        AgentSession session = AgentSession.builder()
                .id(CHILD_SESSION).userId(USER_ID).connectionId(UUID.randomUUID())
                .parentSessionId(parent).title("Банк В").build();
        when(agentSessionRepository.findById(CHILD_SESSION)).thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("не ран субагента — не наш отчёт, claim не трогается")
    void notASubagentRun() {
        childSession(null);

        assertTrue(delivery.prepare(CHILD_RUN, false, "text").isEmpty());
        verify(agentRunRepository, never()).claimReport(any(), any());
    }

    @Test
    @DisplayName("остановленный пользователем ребёнок не отчитывается")
    void cancelledChildSilent() {
        childSession(CONVERSATION);
        child.setCancelRequestedAt(LocalDateTime.now());

        assertTrue(delivery.prepare(CHILD_RUN, false, "text").isEmpty());
        verify(agentRunRepository, never()).claimReport(any(), any());
    }

    @Test
    @DisplayName("claim уже взят — второй отчёт не создаётся")
    void alreadyReported() {
        childSession(CONVERSATION);
        when(agentRunRepository.claimReport(eq(CHILD_RUN), any())).thenReturn(0);

        assertTrue(delivery.prepare(CHILD_RUN, false, "text").isEmpty());
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("кто-то ещё работает — ответ только в историю разговора")
    void othersWorkingHistoryOnly() {
        childSession(CONVERSATION);
        when(agentRunRepository.claimReport(eq(CHILD_RUN), any())).thenReturn(1);
        when(subagentService.countWorking(CONVERSATION)).thenReturn(2L);

        SubagentReportDelivery.Prepared prepared = delivery.prepare(CHILD_RUN, false, "отчёт").orElseThrow();

        assertEquals(CONVERSATION, prepared.run().getSessionId());
        assertEquals(CHILD_RUN, prepared.run().getOriginRunId());
        assertNull(prepared.channels().prompt());
        assertNull(prepared.channels().answer().channelId());
        assertEquals(CONVERSATION, prepared.channels().answer().sessionId());
        assertEquals(2L, prepared.trigger().data().get("remaining"));
        assertEquals("отчёт", prepared.trigger().data().get("report"));
        assertEquals(CHILD_SESSION.toString(), prepared.trigger().data().get("subagentId"));
    }

    @Test
    @DisplayName("последний отчёт — каналы последнего диалогового рана разговора, без prompt")
    void lastReportReachesTheChat() {
        childSession(CONVERSATION);
        when(agentRunRepository.claimReport(eq(CHILD_RUN), any())).thenReturn(1);
        when(subagentService.countWorking(CONVERSATION)).thenReturn(0L);
        ChannelInfo chat = new ChannelInfo(CHAT_CHANNEL, CONVERSATION, null);
        AgentRun dialogue = AgentRun.builder().channels(ChannelsCodec.toMap(Channels.ofPrompt(chat))).build();
        when(agentRunRepository.findLatestDialogueRun(CONVERSATION)).thenReturn(Optional.of(dialogue));

        SubagentReportDelivery.Prepared prepared = delivery.prepare(CHILD_RUN, true, "упал").orElseThrow();

        assertNull(prepared.channels().prompt());
        assertEquals(CHAT_CHANNEL, prepared.channels().answer().channelId());
        Trigger trigger = prepared.trigger();
        assertEquals("subagents", trigger.connectorCode());
        assertEquals("report_received", trigger.name());
        assertEquals("failed", trigger.data().get("status"));
        assertEquals("упал", trigger.data().get("error"));
        ArgumentCaptor<AgentRun> saved = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunRepository).save(saved.capture());
        assertEquals(CHILD_RUN, saved.getValue().getOriginRunId());
    }
}
