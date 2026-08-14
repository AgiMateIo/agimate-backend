package ru.agimate.controlapi.service.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
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
            }, AcpSessionRegistry.ClientCapabilities.NONE, null);
            registry.sendUpdate(SESSION_ID, Map.of("sessionUpdate", "agent_message_chunk"));
        }
    }

    @Nested
    @DisplayName("prompt lifecycle")
    class PromptLifecycle {

        @Test
        @DisplayName("completePrompt отвечает на зарегистрированный rpc-id и снимает pending")
        void completeSendsResult() {
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
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
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
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
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
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
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
            registry.attach(otherSession, other, AcpSessionRegistry.ClientCapabilities.NONE, null);

            registry.detachAll(client);

            registry.sendUpdate(SESSION_ID, Map.of("k", "v"));
            registry.sendUpdate(otherSession, Map.of("k", "v"));
            assertTrue(client.frames.isEmpty());
            assertEquals(1, other.frames.size());
        }
    }

    @Nested
    @DisplayName("server→client request (IDE-тулы)")
    class ServerRequest {

        private static final AcpSessionRegistry.ClientCapabilities FULL =
                new AcpSessionRegistry.ClientCapabilities(true, true, true);

        @Test
        @DisplayName("request шлёт JSON-RPC запрос; handleResponse завершает future результатом")
        void requestResolvedByResponse() throws Exception {
            registry.attach(SESSION_ID, client, FULL, null);
            CompletableFuture<JsonNode> future =
                    registry.request(SESSION_ID, "fs/read_text_file", Map.of("path", "/a"));

            Map<String, Object> frame = client.frames.get(0);
            assertEquals("fs/read_text_file", frame.get("method"));
            String id = (String) frame.get("id");
            assertTrue(id.startsWith("srv-"));
            assertFalse(future.isDone());

            registry.handleResponse(id, JsonUtils.toJsonNodeOrNull("{\"content\":\"hi\"}"), null);
            assertEquals("hi", future.get(1, TimeUnit.SECONDS).path("content").asText());
        }

        @Test
        @DisplayName("handleResponse с error завершает future исключением")
        void errorResponseFailsFuture() {
            registry.attach(SESSION_ID, client, FULL, null);
            CompletableFuture<JsonNode> future = registry.request(SESSION_ID, "fs/write_text_file", Map.of());
            String id = (String) client.frames.get(0).get("id");

            registry.handleResponse(id, null, JsonUtils.toJsonNodeOrNull("{\"code\":-32000,\"message\":\"nope\"}"));

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause().getMessage().contains("nope"));
        }

        @Test
        @DisplayName("request на непривязанную сессию — IllegalStateException")
        void requestWithoutSession() {
            assertThrows(IllegalStateException.class,
                    () -> registry.request(SESSION_ID, "fs/read_text_file", Map.of()));
        }

        @Test
        @DisplayName("обрыв соединения завершает висящие запросы ошибкой")
        void detachFailsPendingRequests() {
            registry.attach(SESSION_ID, client, FULL, null);
            CompletableFuture<JsonNode> future = registry.request(SESSION_ID, "terminal/create", Map.of());

            registry.detachAll(client);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause().getMessage().contains("disconnected"));
        }

        @Test
        @DisplayName("capabilities возвращает объявленные клиентом флаги")
        void capabilitiesStored() {
            registry.attach(SESSION_ID, client, FULL, null);
            assertTrue(registry.capabilities(SESSION_ID).fsWrite());
            assertTrue(registry.capabilities(UUID.randomUUID()) == AcpSessionRegistry.ClientCapabilities.NONE);
        }
    }

    @Nested
    @DisplayName("session MCP-тулы")
    class McpTools {

        private final ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec spec =
                new ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec(
                        "srv__t", null, "d", null, null, null, null, null);

        @Test
        @DisplayName("putMcpTools кладёт спеки и ссылки, доступные по имени")
        void storeAndLookup() {
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
            registry.putMcpTools(SESSION_ID, Map.of("srv__t", spec),
                    Map.of("srv__t", new AcpSessionRegistry.McpToolRef("srv", "t")));

            assertTrue(registry.mcpToolSpecs(SESSION_ID).containsKey("srv__t"));
            assertEquals("srv", registry.mcpToolRef(SESSION_ID, "srv__t").server());
            assertEquals("t", registry.mcpToolRef(SESSION_ID, "srv__t").rawName());
            assertEquals(spec, registry.mcpToolSpec(SESSION_ID, "srv__t"));
        }

        @Test
        @DisplayName("detach чистит MCP-тулы сессии")
        void clearedOnDetach() {
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
            registry.putMcpTools(SESSION_ID, Map.of("srv__t", spec),
                    Map.of("srv__t", new AcpSessionRegistry.McpToolRef("srv", "t")));

            registry.detachAll(client);

            assertTrue(registry.mcpToolSpecs(SESSION_ID).isEmpty());
            assertTrue(registry.mcpToolRef(SESSION_ID, "srv__t") == null);
        }

        @Test
        @DisplayName("пустой список — чистка (нет MCP-серверов в сессии)")
        void emptyClears() {
            registry.attach(SESSION_ID, client, AcpSessionRegistry.ClientCapabilities.NONE, null);
            registry.putMcpTools(SESSION_ID, Map.of(), Map.of());
            assertTrue(registry.mcpToolSpecs(SESSION_ID).isEmpty());
        }
    }
}
