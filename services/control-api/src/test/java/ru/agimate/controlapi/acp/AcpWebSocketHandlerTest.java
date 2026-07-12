package ru.agimate.controlapi.acp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.acp.AcpService;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AcpWebSocketHandler")
class AcpWebSocketHandlerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AcpService acpService;
    @Mock
    private AcpSessionRegistry sessionRegistry;
    @Mock
    private WebSocketSession wsSession;

    private AcpWebSocketHandler handler;
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<Map<String, Object>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        handler = new AcpWebSocketHandler(acpService, sessionRegistry);
        attributes.put(AcpHandshakeInterceptor.ATTR_PRINCIPAL, new AgentPrincipal("key", AGENT_ID, USER_ID));
        when(wsSession.getAttributes()).thenReturn(attributes);
        lenient().when(wsSession.getId()).thenReturn("ws-1");
        lenient().doAnswer(inv -> {
            WebSocketMessage<?> msg = inv.getArgument(0);
            sent.add(JsonUtils.fromJsonToMap(((TextMessage) msg).getPayload()));
            return null;
        }).when(wsSession).sendMessage(any());
        handler.afterConnectionEstablished(wsSession);
    }

    private void receive(Object frame) {
        handler.handleTextMessage(wsSession, new TextMessage(JsonUtils.writeValueAsString(frame)));
    }

    private Map<String, Object> request(Object id, String method, Map<String, Object> params) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("method", method);
        if (params != null) {
            frame.put("params", params);
        }
        return frame;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleResponse() {
        assertEquals(1, sent.size());
        return sent.get(0);
    }

    @Nested
    @DisplayName("initialize")
    class Initialize {

        @Test
        @DisplayName("возвращает protocolVersion=1, loadSession=true, authMethods=[]")
        @SuppressWarnings("unchecked")
        void initialize() {
            receive(request(1, "initialize", Map.of("protocolVersion", 1)));

            Map<String, Object> result = (Map<String, Object>) singleResponse().get("result");
            assertEquals(1, result.get("protocolVersion"));
            Map<String, Object> caps = (Map<String, Object>) result.get("agentCapabilities");
            assertEquals(true, caps.get("loadSession"));
            assertTrue(((List<?>) result.get("authMethods")).isEmpty());
        }
    }

    @Nested
    @DisplayName("session/new")
    class SessionNew {

        @Test
        @DisplayName("создаёт сессию от принципала ключа, привязывает её и возвращает sessionId")
        @SuppressWarnings("unchecked")
        void createsSession() {
            ChannelSession channelSession = mock(ChannelSession.class);
            when(channelSession.getId()).thenReturn(SESSION_ID);
            when(acpService.startSession(USER_ID, AGENT_ID)).thenReturn(channelSession);

            receive(request("r1", "session/new", Map.of("cwd", "/tmp")));

            verify(sessionRegistry).attach(eq(SESSION_ID), any());
            Map<String, Object> result = (Map<String, Object>) singleResponse().get("result");
            assertEquals(SESSION_ID.toString(), result.get("sessionId"));
        }
    }

    @Nested
    @DisplayName("session/prompt")
    class SessionPrompt {

        @Test
        @DisplayName("склеивает text-блоки, регистрирует pending и не отвечает сразу")
        void routesPrompt() {
            receive(request(7, "session/prompt", Map.of(
                    "sessionId", SESSION_ID.toString(),
                    "prompt", List.of(
                            Map.of("type", "text", "text", "строка 1"),
                            Map.of("type", "text", "text", "строка 2")))));

            verify(sessionRegistry).registerPrompt(eq(SESSION_ID), any());
            verify(acpService).prompt(USER_ID, AGENT_ID, SESSION_ID, "строка 1\nстрока 2");
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("не-текстовый блок → invalid params, prompt не регистрируется")
        void rejectsNonText() {
            receive(request(8, "session/prompt", Map.of(
                    "sessionId", SESSION_ID.toString(),
                    "prompt", List.of(Map.of("type", "image", "data", "...")))));

            assertError(-32602);
        }

        @Test
        @DisplayName("ошибка маршрутизации завершает pending через failPrompt")
        void routingFailureFailsPrompt() {
            doThrow(new BadRequestStatusException("ACP session is closed"))
                    .when(acpService).prompt(any(), any(), any(), any());

            receive(request(9, "session/prompt", Map.of(
                    "sessionId", SESSION_ID.toString(),
                    "prompt", List.of(Map.of("type", "text", "text", "hi")))));

            verify(sessionRegistry).failPrompt(SESSION_ID, -32602, "ACP session is closed");
            assertTrue(sent.isEmpty());
        }
    }

    @Nested
    @DisplayName("прочие фреймы")
    class Misc {

        @Test
        @DisplayName("session/cancel мягко завершает pending со stopReason=cancelled")
        void cancelReleasesPrompt() {
            Map<String, Object> frame = new HashMap<>();
            frame.put("jsonrpc", "2.0");
            frame.put("method", "session/cancel");
            frame.put("params", Map.of("sessionId", SESSION_ID.toString()));
            receive(frame);

            verify(sessionRegistry).completePrompt(SESSION_ID, AcpSessionRegistry.STOP_CANCELLED);
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("неизвестный метод → -32601")
        void unknownMethod() {
            receive(request(2, "session/set_mode", Map.of()));
            assertError(-32601);
        }

        @Test
        @DisplayName("битый JSON → -32700")
        void parseError() {
            handler.handleTextMessage(wsSession, new TextMessage("{oops"));
            assertError(-32700);
        }

        @Test
        @DisplayName("закрытие соединения отвязывает все его сессии")
        void closeDetaches() {
            handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
            verify(sessionRegistry).detachAll(any());
        }
    }

    @SuppressWarnings("unchecked")
    private void assertError(int code) {
        Map<String, Object> error = (Map<String, Object>) singleResponse().get("error");
        assertNotNull(error);
        assertEquals(code, error.get("code"));
    }
}
