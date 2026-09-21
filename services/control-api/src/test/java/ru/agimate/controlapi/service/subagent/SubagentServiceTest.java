package ru.agimate.controlapi.service.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubagentService")
class SubagentServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private AgentSessionService agentSessionService;
    @Mock private AgentRepository agentRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ChannelService channelService;
    @Mock private AgentDeliveryService agentDeliveryService;

    private SubagentService service;
    private Agent agent;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        service = new SubagentService(agentRunRepository, agentSessionRepository, agentSessionService,
                agentRepository, channelRepository, channelService, agentDeliveryService);
        agent = Agent.builder().id(AGENT_ID).userId(USER_ID).name("Bot").type(AgentType.GENERIC).build();
        run = AgentRun.builder()
                .agent(agent)
                .sessionId(CONVERSATION)
                .channels(ChannelsCodec.toMap(Channels.ofPrompt(new ChannelInfo(UUID.randomUUID(), CONVERSATION, null))))
                .build();
        run.setId(RUN_ID);
        lenient().when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(agentDeliveryService.supportsPush(agent)).thenReturn(true);
        lenient().when(agentSessionRepository.lockById(CONVERSATION)).thenReturn(Optional.of(conversation(null)));
    }

    private static AgentSession conversation(UUID parent) {
        return AgentSession.builder().id(CONVERSATION).agentId(AGENT_ID).parentSessionId(parent).build();
    }

    private SubagentService.Target open(UUID subagentId) {
        return service.open(AGENT_ID, RUN_ID, CONNECTION_ID.toString(), "Банк В", subagentId);
    }

    @Nested
    @DisplayName("отказы")
    class Refusals {

        @Test
        @DisplayName("ран без разговора (cron, вебхук) — отчёту некуда вернуться")
        void noConversation() {
            run.setChannels(null);

            ConnectorException e = assertThrows(ConnectorException.class, () -> open(null));
            assertTrue(e.getMessage().contains("conversation"));
        }

        @Test
        @DisplayName("субагент не поручает субагентам — глубина 1")
        void depthOne() {
            when(agentSessionRepository.lockById(CONVERSATION)).thenReturn(Optional.of(conversation(UUID.randomUUID())));

            assertThrows(ConnectorException.class, () -> open(null));
            verify(agentSessionService, never()).createChild(any(), any(), any());
        }

        @Test
        @DisplayName("кап работающих детей разговора")
        void cap() {
            when(agentSessionRepository.countWorkingChildren(eq(CONVERSATION), any()))
                    .thenReturn((long) SubagentService.MAX_WORKING);

            assertThrows(ConnectorException.class, () -> open(null));
            verify(agentSessionService, never()).createChild(any(), any(), any());
        }

        @Test
        @DisplayName("чужой или закрытый subagentId — отказ, а не переадресация")
        void foreignOrClosedChild() {
            UUID foreign = UUID.randomUUID();
            when(agentSessionRepository.findById(foreign)).thenReturn(Optional.of(
                    AgentSession.builder().id(foreign).agentId(AGENT_ID).parentSessionId(UUID.randomUUID()).build()));
            UUID closed = UUID.randomUUID();
            when(agentSessionRepository.findById(closed)).thenReturn(Optional.of(
                    AgentSession.builder().id(closed).agentId(AGENT_ID).parentSessionId(CONVERSATION)
                            .closedAt(LocalDateTime.now()).build()));

            assertThrows(ConnectorException.class, () -> open(foreign));
            assertThrows(ConnectorException.class, () -> open(closed));
        }
    }

    @Test
    @DisplayName("новое поручение — сессия ребёнка в существующем канале агента")
    void newChild() {
        Channel channel = Channel.builder().id(CHANNEL_ID).agentId(AGENT_ID).build();
        when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                AGENT_ID, "subagents", CONNECTION_ID)).thenReturn(Optional.of(channel));
        UUID childId = UUID.randomUUID();
        when(agentSessionService.createChild(channel, CONVERSATION, "Банк В"))
                .thenReturn(AgentSession.builder().id(childId).build());

        SubagentService.Target target = open(null);

        assertEquals(SubagentService.Mode.NEW, target.mode());
        assertEquals(CHANNEL_ID, target.channelId());
        assertEquals(childId, target.childSessionId());
        assertEquals(CONVERSATION, target.conversationId());
        verify(channelService, never()).create(any(), any());
    }

    @Nested
    @DisplayName("поручение другому агенту")
    class OtherAgent {

        private final Agent lawyer = Agent.builder().id(UUID.randomUUID()).userId(USER_ID).name("Юрист")
                .type(AgentType.GENERIC).build();
        private final SubagentService.Callee callee = new SubagentService.Callee(
                lawyer, "agents", "agents", "Agents: Юрист");

        private SubagentService.Target openFor(UUID childId) {
            return service.openFor(callee, AGENT_ID, RUN_ID, CONNECTION_ID.toString(), "Договор", childId);
        }

        @Test
        @DisplayName("новая ветка — сессия в канале agents адресата, канал создаётся у адресата")
        void newThreadInCalleesChannel() {
            when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                    lawyer.getId(), "agents", CONNECTION_ID)).thenReturn(Optional.empty());
            Channel channel = Channel.builder().id(CHANNEL_ID).agentId(lawyer.getId()).build();
            when(channelService.create(eq(USER_ID), any())).thenReturn(channel);
            UUID threadId = UUID.randomUUID();
            when(agentSessionService.createChild(channel, CONVERSATION, "Договор"))
                    .thenReturn(AgentSession.builder().id(threadId).build());

            SubagentService.Target target = openFor(null);

            assertEquals(SubagentService.Mode.NEW, target.mode());
            assertEquals(threadId, target.childSessionId());
            ArgumentCaptor<ChannelService.CreateChannelData> data =
                    ArgumentCaptor.forClass(ChannelService.CreateChannelData.class);
            verify(channelService).create(eq(USER_ID), data.capture());
            assertEquals(lawyer.getId(), data.getValue().agentId());
            assertEquals("agents", data.getValue().connectorCode());
            assertEquals("Agents: Юрист", data.getValue().name());
        }

        @Test
        @DisplayName("дописать можно только в ветку этого адресата")
        void appendOnlyToCalleesThread() {
            UUID own = UUID.randomUUID();
            when(agentSessionRepository.findById(own)).thenReturn(Optional.of(
                    AgentSession.builder().id(own).agentId(AGENT_ID).parentSessionId(CONVERSATION).build()));
            UUID lawyers = UUID.randomUUID();
            when(agentSessionRepository.findById(lawyers)).thenReturn(Optional.of(
                    AgentSession.builder().id(lawyers).agentId(lawyer.getId()).channelId(CHANNEL_ID)
                            .parentSessionId(CONVERSATION).build()));

            assertThrows(ConnectorException.class, () -> openFor(own));
            assertEquals(SubagentService.Mode.APPEND, openFor(lawyers).mode());
        }

        @Test
        @DisplayName("кап общий: субагенты и агенты считаются вместе")
        void sharedCap() {
            when(agentSessionRepository.countWorkingChildren(eq(CONVERSATION), any()))
                    .thenReturn((long) SubagentService.MAX_WORKING);

            assertThrows(ConnectorException.class, () -> openFor(null));
        }
    }

    @Test
    @DisplayName("дописывание — тот же ребёнок, кап не проверяется")
    void appendToChild() {
        UUID childId = UUID.randomUUID();
        when(agentSessionRepository.findById(childId)).thenReturn(Optional.of(
                AgentSession.builder().id(childId).agentId(AGENT_ID).channelId(CHANNEL_ID)
                        .parentSessionId(CONVERSATION).build()));

        SubagentService.Target target = open(childId);

        assertEquals(SubagentService.Mode.APPEND, target.mode());
        assertEquals(childId, target.childSessionId());
        verify(agentSessionRepository, never()).countWorkingChildren(any(), any());
    }
}
