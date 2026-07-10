package ru.agimate.controlapi.service.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendMessageRequest;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSessionResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebchatService")
class WebchatServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelService channelService;
    @Mock
    private ChannelSessionService channelSessionService;
    @Mock
    private ConnectionBindingService connectionBindingService;
    @Mock
    private TriggerRouterService triggerRouterService;
    @Mock
    private WebchatMessagePublisher webchatMessagePublisher;
    @Mock
    private WebchatMessageRepository webchatMessageRepository;
    @Mock
    private CentrifugoService centrifugoService;

    private WebchatService webchatService;

    private Agent agent;
    private Channel channel;
    private ChannelSession session;

    @BeforeEach
    void setUp() {
        webchatService = new WebchatService(agentRepository, channelRepository, channelService,
                channelSessionService, connectionBindingService, triggerRouterService,
                webchatMessagePublisher, webchatMessageRepository, centrifugoService);
        agent = Agent.builder().id(AGENT_ID).userId(USER_ID).name("Assistant").build();
        channel = Channel.builder()
                .id(CHANNEL_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode("webchat")
                .connectionId(CONNECTION_ID)
                .build();
        session = ChannelSession.builder().id(SESSION_ID).channelId(CHANNEL_ID).build();
    }

    private AgentConnection binding() {
        return AgentConnection.builder().agentId(AGENT_ID).connectionId(CONNECTION_ID).build();
    }

    @Nested
    @DisplayName("startSession — binding и канал лениво, сессия всегда новая")
    class StartSession {

        @Test
        @DisplayName("существующий канал переиспользуется")
        void reusesChannel() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
            when(connectionBindingService.bind(USER_ID, AGENT_ID, "webchat", null, null))
                    .thenReturn(binding());
            when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                    AGENT_ID, "webchat", CONNECTION_ID)).thenReturn(Optional.of(channel));
            when(channelSessionService.createNew(channel, null)).thenReturn(session);

            WebchatSessionResponse response = webchatService.startSession(USER_ID, AGENT_ID);

            assertEquals(SESSION_ID, response.sessionId());
            assertEquals(AGENT_ID, response.agentId());
            verify(channelService, never()).create(any(), any());
        }

        @Test
        @DisplayName("без канала — создаётся через ChannelService с handler'ом webchat")
        void createsChannel() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
            when(connectionBindingService.bind(USER_ID, AGENT_ID, "webchat", null, null))
                    .thenReturn(binding());
            when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                    AGENT_ID, "webchat", CONNECTION_ID)).thenReturn(Optional.empty());
            when(channelService.create(eq(USER_ID), any())).thenReturn(channel);
            when(channelSessionService.createNew(channel, null)).thenReturn(session);

            webchatService.startSession(USER_ID, AGENT_ID);

            ArgumentCaptor<ChannelService.CreateChannelData> data =
                    ArgumentCaptor.forClass(ChannelService.CreateChannelData.class);
            verify(channelService).create(eq(USER_ID), data.capture());
            assertEquals(AGENT_ID, data.getValue().agentId());
            assertEquals("webchat", data.getValue().channelHandler());
            assertEquals("webchat", data.getValue().connectorCode());
            assertEquals(CONNECTION_ID.toString(), data.getValue().connectionId());
        }
    }

    @Nested
    @DisplayName("send — UI-строка + echo + триггер с audience и declared session")
    class Send {

        private void stubOwnedSession() {
            when(channelSessionService.getById(SESSION_ID)).thenReturn(session);
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
        }

        @Test
        @DisplayName("триггер несёт webchat/connectionId, targetAgentIds=[agent] и prompt(channel, session)")
        void routesDirectedTrigger() {
            stubOwnedSession();

            WebchatSendResponse response = webchatService.send(USER_ID, SESSION_ID,
                    new WebchatSendMessageRequest("привет", null));

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.USER, null, response.messageId(), "привет");
            verify(channelSessionService).setTitleIfEmpty(session, "привет");

            ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
            verify(triggerRouterService).routeTrigger(eq(USER_ID), captor.capture());
            Trigger trigger = captor.getValue();
            assertEquals("webchat", trigger.connectorCode());
            assertEquals(CONNECTION_ID.toString(), trigger.connectionId());
            assertEquals("message_received", trigger.name());
            assertEquals("привет", trigger.data().get("text"));
            assertEquals(SESSION_ID.toString(), trigger.data().get("sessionId"));
            assertEquals(response.messageId(), trigger.data().get("messageId"));
            assertEquals(List.of(AGENT_ID), trigger.context().audience().targetAgentIds());
            assertEquals(CHANNEL_ID, trigger.context().channels().prompt().channelId());
            assertEquals(SESSION_ID, trigger.context().channels().prompt().sessionId());
        }

        @Test
        @DisplayName("закрытая сессия — 400, ничего не отправляется")
        void closedSessionRejected() {
            session.setClosedAt(LocalDateTime.now());
            stubOwnedSession();

            assertThrows(BadRequestStatusException.class, () -> webchatService.send(USER_ID, SESSION_ID,
                    new WebchatSendMessageRequest("привет", null)));
            verifyNoInteractions(triggerRouterService, webchatMessagePublisher);
        }

        @Test
        @DisplayName("непустые parts — 400 (вложения пока не поддержаны)")
        void partsRejected() {
            stubOwnedSession();

            assertThrows(BadRequestStatusException.class, () -> webchatService.send(USER_ID, SESSION_ID,
                    new WebchatSendMessageRequest("привет", List.of(Map.of("type", "image")))));
            verifyNoInteractions(triggerRouterService, webchatMessagePublisher);
        }

        @Test
        @DisplayName("чужая сессия — Forbidden")
        void foreignSessionForbidden() {
            channel.setUserId(UUID.randomUUID());
            stubOwnedSession();

            assertThrows(ForbiddenStatusException.class, () -> webchatService.send(USER_ID, SESSION_ID,
                    new WebchatSendMessageRequest("привет", null)));
            verifyNoInteractions(triggerRouterService, webchatMessagePublisher);
        }

        @Test
        @DisplayName("не-webchat канал — 400")
        void nonWebchatSessionRejected() {
            channel.setConnectorCode("telegram");
            stubOwnedSession();

            assertThrows(BadRequestStatusException.class, () -> webchatService.send(USER_ID, SESSION_ID,
                    new WebchatSendMessageRequest("привет", null)));
        }
    }

    @Nested
    @DisplayName("token — подписка на webchat:{sessionId}")
    class Token {

        @Test
        @DisplayName("проверяет владение сессией и делегирует выпуск токенов на канал webchat:{sessionId}")
        void issuesTokens() {
            when(channelSessionService.getById(SESSION_ID)).thenReturn(session);
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            CentrifugoTokenResponse expected = new CentrifugoTokenResponse(
                    "conn", "sub", "webchat:" + SESSION_ID, "wss://c.example/connection/websocket");
            when(centrifugoService.issueTokens(USER_ID.toString(), "webchat:" + SESSION_ID))
                    .thenReturn(expected);

            CentrifugoTokenResponse response = webchatService.token(USER_ID, SESSION_ID);

            assertSame(expected, response);
        }
    }

    @Nested
    @DisplayName("listSessions")
    class ListSessions {

        @Test
        @DisplayName("сессии всех webchat-каналов пользователя с agentId из канала")
        void listsAcrossAgents() {
            when(channelRepository.findByUserIdAndConnectorCodeAndDeletedAtIsNull(USER_ID, "webchat"))
                    .thenReturn(List.of(channel));
            when(channelSessionService.listByChannelIds(List.of(CHANNEL_ID)))
                    .thenReturn(List.of(session));

            List<WebchatSessionResponse> sessions = webchatService.listSessions(USER_ID, null);

            assertEquals(1, sessions.size());
            assertEquals(AGENT_ID, sessions.get(0).agentId());
            assertNull(sessions.get(0).closedAt());
        }

        @Test
        @DisplayName("фильтр по агенту отсекает чужие каналы")
        void filtersByAgent() {
            when(channelRepository.findByUserIdAndConnectorCodeAndDeletedAtIsNull(USER_ID, "webchat"))
                    .thenReturn(List.of(channel));

            List<WebchatSessionResponse> sessions = webchatService.listSessions(USER_ID, UUID.randomUUID());

            assertEquals(0, sessions.size());
        }
    }
}
