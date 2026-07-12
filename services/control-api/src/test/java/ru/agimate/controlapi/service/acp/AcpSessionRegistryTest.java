package ru.agimate.controlapi.service.acp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AcpSessionRegistry")
class AcpSessionRegistryTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    private AcpSessionRegistry registry;
    private RecordingClient client;

    private static class RecordingClient implements AcpSessionRegistry.Client {
        final List<Map<String, Object>> frames = new ArrayList<>();

        @Override
        public void send(String frame) {
            frames.add(JsonUtils.fromJsonToMap(frame));
        }
    }

    @BeforeEach
    void setUp() {
        registry = new AcpSessionRegistry();
        client = new RecordingClient();
    }

    @Nested
    @DisplayName("sendUpdate")
    class SendUpdate {

        @Test
        @DisplayName("привязанная сессия получает нотификацию session/update")
        void deliversNotification() {
            registry.attach(SESSION_ID, client);
            registry.sendUpdate(SESSION_ID, Map.of("sessionUpdate", "agent_message_chunk"));

            assertEquals(1, client.frames.size());
            Map<String, Object> frame = client.frames.get(0);
            assertEquals("session/update", frame.get("method"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) frame.get("params");
            assertEquals(SESSION_ID.toString(), params.get("sessionId"));
        }

        @Test
        @DisplayName("непривязанная сессия — no-op (history-only)")
        void noopWhenDetached() {
            registry.sendUpdate(SESSION_ID, Map.of("sessionUpdate", "agent_message_chunk"));
            assertTrue(client.frames.isEmpty());
        }

        @Test
        @DisplayName("исключение из send не пробрасывается (best-effort)")
        void sendFailureSwallowed() {
            registry.attach(SESSION_ID, frame -> {
                throw new RuntimeException("socket closed");
            });
            registry.sendUpdate(SESSION_ID, Map.of("sessionUpdate", "agent_message_chunk"));
        }
    }

    @Nested
    @DisplayName("prompt lifecycle")
    class PromptLifecycle {

        @Test
        @DisplayName("completePrompt отвечает на зарегистрированный rpc-id и снимает pending")
        void completeSendsResult() {
            registry.attach(SESSION_ID, client);
            registry.registerPrompt(SESSION_ID, 42);

            assertTrue(registry.completePrompt(SESSION_ID, AcpSessionRegistry.STOP_END_TURN));

            Map<String, Object> frame = client.frames.get(0);
            assertEquals(42, frame.get("id"));
            assertEquals(Map.of("stopReason", "end_turn"), frame.get("result"));
            // повторное завершение — no-op
            assertFalse(registry.completePrompt(SESSION_ID, AcpSessionRegistry.STOP_END_TURN));
        }

        @Test
        @DisplayName("failPrompt отвечает JSON-RPC ошибкой")
        void failSendsError() {
            registry.attach(SESSION_ID, client);
            registry.registerPrompt(SESSION_ID, "req-1");

            assertTrue(registry.failPrompt(SESSION_ID, -32000, "boom"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) client.frames.get(0).get("error");
            assertEquals(-32000, error.get("code"));
            assertEquals("boom", error.get("message"));
        }

        @Test
        @DisplayName("второй prompt при незавершённом первом — IllegalStateException")
        void doublePromptRejected() {
            registry.attach(SESSION_ID, client);
            registry.registerPrompt(SESSION_ID, 1);
            assertThrows(IllegalStateException.class, () -> registry.registerPrompt(SESSION_ID, 2));
        }

        @Test
        @DisplayName("prompt на непривязанную сессию — IllegalStateException")
        void promptWithoutAttachRejected() {
            assertThrows(IllegalStateException.class, () -> registry.registerPrompt(SESSION_ID, 1));
        }
    }

    @Nested
    @DisplayName("detachAll")
    class Detach {

        @Test
        @DisplayName("снимает все сессии соединения; чужие остаются")
        void removesOnlyOwnSessions() {
            RecordingClient other = new RecordingClient();
            UUID otherSession = UUID.randomUUID();
            registry.attach(SESSION_ID, client);
            registry.attach(otherSession, other);

            registry.detachAll(client);

            registry.sendUpdate(SESSION_ID, Map.of("k", "v"));
            registry.sendUpdate(otherSession, Map.of("k", "v"));
            assertTrue(client.frames.isEmpty());
            assertEquals(1, other.frames.size());
        }
    }
}
