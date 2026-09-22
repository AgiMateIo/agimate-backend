package ru.agimate.controlapi.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatContactResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.realtime.RealtimeEvent.AgentDelivery;
import ru.agimate.controlapi.realtime.RealtimeEvent.AgentRequestChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.AppToolCall;
import ru.agimate.controlapi.realtime.RealtimeEvent.BoardTaskChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.SessionChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.WebchatMessageRecorded;
import ru.agimate.controlapi.realtime.RealtimeMessages.Message;
import ru.agimate.controlapi.realtime.dto.CentrifugoMessage;
import ru.agimate.controlapi.realtime.dto.ToolCallPayload;
import ru.agimate.controlapi.realtime.dto.WebchatActivityPayload;
import ru.agimate.controlapi.realtime.dto.WebchatMessagePayload;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestResponse;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.session.SessionRows;
import ru.agimate.controlapi.service.team.AgentRequestQueryService;
import ru.agimate.controlapi.service.team.AgentRequestQueryService.TeamRequest;
import ru.agimate.controlapi.service.webchat.ContactRows;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RealtimeMessages — событие → канал, тип, теги, нагрузка")
class RealtimeMessagesTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String USER = "user:" + USER_ID;

    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private SessionRows sessionRows;
    @Mock private ContactRows contactRows;
    @Mock private AgentRequestQueryService agentRequestQueryService;
    @Mock private SignedFileUrlService signedFileUrlService;
    @InjectMocks private RealtimeMessages messages;

    private static Object payload(Message message) {
        return ((CentrifugoMessage<?>) message.data()).payload();
    }

    private static String type(Message message) {
        return ((CentrifugoMessage<?>) message.data()).type();
    }

    @Nested
    @DisplayName("SessionChanged")
    class Sessions {

        private final SessionResponse row = mock(SessionResponse.class);

        private void session(String connectorCode) {
            AgentSession session = AgentSession.builder().id(SESSION_ID).userId(USER_ID).agentId(AGENT_ID)
                    .connectorCode(connectorCode).build();
            when(agentSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            when(sessionRows.of(session)).thenReturn(row);
        }

        @Test
        @DisplayName("новая сессия не веб-чата — одна строка session.created, контакт не трогается")
        void createdOtherConnector() {
            session("telegram");

            List<Message> out = messages.render(SessionChanged.created(SESSION_ID));

            assertEquals(1, out.size());
            assertEquals(USER, out.get(0).channel());
            assertEquals("session.created", type(out.get(0)));
            assertSame(row, payload(out.get(0)));
            assertEquals(Map.of("entity", "session", "agentId", AGENT_ID.toString()), out.get(0).tags());
            verifyNoInteractions(contactRows);
        }

        @Test
        @DisplayName("сессия веб-чата — вслед за строкой сессии строка контакта")
        void webchatAlsoMovesContact() {
            session("webchat");
            WebchatContactResponse contact = mock(WebchatContactResponse.class);
            when(contactRows.find(AGENT_ID)).thenReturn(Optional.of(contact));

            List<Message> out = messages.render(SessionChanged.updated(SESSION_ID));

            assertEquals(List.of("session.updated", "webchat.agent.updated"), out.stream().map(RealtimeMessagesTest::type).toList());
            assertSame(contact, payload(out.get(1)));
            assertEquals(Map.of("entity", "webchat.agent", "agentId", AGENT_ID.toString()), out.get(1).tags());
        }

        @Test
        @DisplayName("сессии нет — публиковать нечего")
        void missing() {
            when(agentSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

            assertTrue(messages.render(SessionChanged.updated(SESSION_ID)).isEmpty());
        }
    }

    @Nested
    @DisplayName("WebchatMessageRecorded")
    class WebchatMessages {

        private WebchatMessageRecorded message(String direction, String stream, String text,
                                               List<Map<String, Object>> parts) {
            return new WebchatMessageRecorded(USER_ID, AGENT_ID, UUID.randomUUID(), SESSION_ID, "m1",
                    direction, stream, text, parts, "2026-09-22T10:00:00Z");
        }

        @Test
        @DisplayName("ответ агента — в user:, в старый webchat:{id} и бейджем с обрезанным превью")
        @SuppressWarnings("deprecation")
        void answerGoesEverywhere() {
            String longText = "а".repeat(WebchatPreviews.MAX_LENGTH + 20);

            List<Message> out = messages.render(message("AGENT", "answer", longText, null));

            assertEquals(3, out.size());
            assertEquals(USER, out.get(0).channel());
            assertEquals("webchat.message", type(out.get(0)));
            assertEquals(Map.of("entity", "webchat.message", "agentId", AGENT_ID.toString(),
                    "sessionId", SESSION_ID.toString()), out.get(0).tags());
            assertEquals("webchat:" + SESSION_ID, out.get(1).channel());
            assertEquals("webchat_message", type(out.get(1)));
            assertEquals("webchat_activity", type(out.get(2)));
            WebchatActivityPayload badge = (WebchatActivityPayload) payload(out.get(2));
            assertEquals(WebchatPreviews.MAX_LENGTH, badge.preview().length());
        }

        @Test
        @DisplayName("progress и своё сообщение бейдж не поднимают")
        void noBadge() {
            assertEquals(2, messages.render(message("AGENT", "progress", "думаю", null)).size());
            assertEquals(2, messages.render(message("USER", null, "привет", null)).size());
        }

        @Test
        @DisplayName("вложение получает свежую подписанную ссылку при отправке")
        void signsAttachments() {
            Map<String, Object> stored = Map.of("type", "image", "fileId", "agf_x", "version", 1,
                    "mime", "image/png", "size", 42L);
            when(signedFileUrlService.issue(new FileLink(USER_ID, "agf_x", "image/png", null, 1)))
                    .thenReturn("/files/agf_x?sig=s");

            List<Message> out = messages.render(message("AGENT", "answer", "вот", List.of(stored)));

            WebchatMessagePayload payload = (WebchatMessagePayload) payload(out.get(0));
            assertEquals("/files/agf_x?sig=s", payload.parts().get(0).url());
        }
    }

    @Test
    @DisplayName("задача доски — в user: с тегами доски")
    void boardTask() {
        UUID boardId = UUID.randomUUID();
        Object task = new Object();

        Message out = messages.render(new BoardTaskChanged(USER_ID, boardId, "board.task.created", task)).get(0);

        assertEquals(USER, out.channel());
        assertEquals("board.task.created", type(out));
        assertSame(task, payload(out));
        assertEquals(Map.of("entity", "board.task", "boardId", boardId.toString()), out.tags());
    }

    @Test
    @DisplayName("поручение — строка списка команды; поручения вне команды не публикуются")
    void agentRequest() {
        UUID thread = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        AgentRequestResponse row = mock(AgentRequestResponse.class);
        when(agentRequestQueryService.forEvent(thread)).thenReturn(Optional.of(new TeamRequest(teamId, USER_ID, row)));

        Message out = messages.render(new AgentRequestChanged(thread, AgentRequestChanged.REPORTED)).get(0);

        assertEquals(USER, out.channel());
        assertEquals(AgentRequestChanged.REPORTED, type(out));
        assertSame(row, payload(out));
        assertEquals(Map.of("entity", "agent.request", "teamId", teamId.toString()), out.tags());

        when(agentRequestQueryService.forEvent(thread)).thenReturn(Optional.empty());
        assertTrue(messages.render(new AgentRequestChanged(thread, AgentRequestChanged.REPORTED)).isEmpty());
    }

    @Test
    @DisplayName("команда агенту — его собственный конверт без обёртки")
    void agentDelivery() {
        AgentMessage<?> message = mock(AgentMessage.class);

        Message out = messages.render(new AgentDelivery(AGENT_ID, message)).get(0);

        assertEquals("agent:" + AGENT_ID, out.channel());
        assertSame(message, out.data());
    }

    @Test
    @DisplayName("вызов тула приложения — toolCall в app:{appId}")
    void appToolCall() {
        UUID appId = UUID.randomUUID();
        ToolCallPayload call = mock(ToolCallPayload.class);

        Message out = messages.render(new AppToolCall(appId, call)).get(0);

        assertEquals("app:" + appId, out.channel());
        assertEquals("toolCall", type(out));
        assertSame(call, payload(out));
    }
}
