package ru.agimate.deviceapi.connectors.integrations.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramPlatformHandlerTest {

    @Mock
    private TelegramApiClient telegramApiClient;

    private TelegramHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new TelegramHandler(telegramApiClient, objectMapper);
    }

    @Test
    @DisplayName("getPlatformCode returns 'telegram'")
    void getPlatformCode() {
        assertEquals("telegram", handler.getPlatformCode());
    }

    @Nested
    @DisplayName("normalizeInbound()")
    class NormalizeInbound {

        private IntegrationCredentials integration;

        @BeforeEach
        void setUp() {
            integration = IntegrationCredentials.builder()
                    .pubId(UUID.randomUUID())
                    .build();
        }

        @Test
        @DisplayName("normalizes text message")
        void textMessage() {
            String rawBody = """
                    {
                        "update_id": 123,
                        "message": {
                            "message_id": 456,
                            "from": {"id": 789, "first_name": "Test"},
                            "chat": {"id": 100, "type": "private"},
                            "text": "Hello world"
                        }
                    }
                    """;

            var result = handler.normalizeInbound(integration, rawBody);

            assertEquals("telegram.message_received", result.name());
            assertNotNull(result.data());
            assertEquals("integration", result.type());
        }

        @Test
        @DisplayName("normalizes command message")
        void commandMessage() {
            String rawBody = """
                    {
                        "update_id": 123,
                        "message": {
                            "message_id": 456,
                            "from": {"id": 789, "first_name": "Test"},
                            "chat": {"id": 100, "type": "private"},
                            "text": "/start hello"
                        }
                    }
                    """;

            var result = handler.normalizeInbound(integration, rawBody);

            assertEquals("telegram.command_received", result.name());
        }

        @Test
        @DisplayName("normalizes photo message")
        void photoMessage() {
            String rawBody = """
                    {
                        "update_id": 123,
                        "message": {
                            "message_id": 456,
                            "from": {"id": 789, "first_name": "Test"},
                            "chat": {"id": 100, "type": "private"},
                            "photo": [{"file_id": "abc", "width": 100, "height": 100}],
                            "caption": "My photo"
                        }
                    }
                    """;

            var result = handler.normalizeInbound(integration, rawBody);

            assertEquals("telegram.photo_received", result.name());
        }

        @Test
        @DisplayName("normalizes callback query")
        void callbackQuery() {
            String rawBody = """
                    {
                        "update_id": 123,
                        "callback_query": {
                            "id": "cb123",
                            "from": {"id": 789, "first_name": "Test"},
                            "data": "button_clicked",
                            "message": {
                                "message_id": 456,
                                "chat": {"id": 100, "type": "private"}
                            }
                        }
                    }
                    """;

            var result = handler.normalizeInbound(integration, rawBody);

            assertEquals("telegram.callback_query", result.name());
        }

        @Test
        @DisplayName("normalizes document message")
        void documentMessage() {
            String rawBody = """
                    {
                        "update_id": 123,
                        "message": {
                            "message_id": 456,
                            "from": {"id": 789, "first_name": "Test"},
                            "chat": {"id": 100, "type": "private"},
                            "document": {"file_id": "doc123", "file_name": "test.pdf"}
                        }
                    }
                    """;

            var result = handler.normalizeInbound(integration, rawBody);

            assertEquals("telegram.document_received", result.name());
        }
    }

    @Nested
    @DisplayName("executeTool()")
    class ExecuteTool {

        private IntegrationCredentials integration;

        @BeforeEach
        void setUp() {
            integration = IntegrationCredentials.builder()
                    .pubId(UUID.randomUUID())
                    .build();
        }

        @Test
        @DisplayName("executes send_message")
        @SuppressWarnings("unchecked")
        void sendMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true, "result", Map.of("message_id", 1));
            when(telegramApiClient.sendMessage(eq("token123"), any())).thenReturn(expectedResponse);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chatId", 100);
            params.put("text", "Hello");

            var result = handler.executeTool(integration, Map.of("token", "token123"),
                    "telegram.send_message", params);

            assertEquals(expectedResponse, result);
            verify(telegramApiClient).sendMessage(eq("token123"), argThat((Map<String, Object> p) ->
                    p.get("chat_id").equals(100) && p.get("text").equals("Hello")));
        }

        @Test
        @DisplayName("executes edit_message")
        void editMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.editMessageText(eq("token123"), any())).thenReturn(expectedResponse);

            Map<String, Object> params = Map.of("chatId", 100, "messageId", 456, "text", "Updated");

            var result = handler.executeTool(integration, Map.of("token", "token123"),
                    "telegram.edit_message", params);

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("executes delete_message")
        void deleteMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.deleteMessage(eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(integration, Map.of("token", "token123"),
                    "telegram.delete_message", Map.of("chatId", 100, "messageId", 456));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("throws for unknown tool")
        void unknownTool() {
            assertThrows(Exception.class, () ->
                    handler.executeTool(integration, Map.of("token", "token123"),
                            "telegram.unknown_tool", Map.of()));
        }
    }

    @Nested
    @DisplayName("validateCredentials()")
    class ValidateCredentials {

        @Test
        @DisplayName("returns success for valid token")
        void validToken() {
            when(telegramApiClient.getMe("valid-token")).thenReturn(
                    Map.of("ok", true, "result", Map.of("username", "test_bot", "first_name", "Test Bot")));

            var result = handler.validateCredentials(Map.of("token", "valid-token"));

            assertTrue(result.valid());
            assertEquals("test_bot", result.identifier());
            assertEquals("Telegram: @test_bot", result.displayName());
        }

        @Test
        @DisplayName("returns failure for invalid token without leaking exception details")
        void invalidToken() {
            when(telegramApiClient.getMe("invalid-token"))
                    .thenThrow(new RuntimeException("401 Unauthorized: /bot123:SECRET/getMe"));

            var result = handler.validateCredentials(Map.of("token", "invalid-token"));

            assertFalse(result.valid());
            assertEquals("token", result.errorField());
            assertEquals("Failed to validate token", result.errorMessage());
        }
    }

    @Nested
    @DisplayName("validateWebhookRequest()")
    class ValidateWebhookRequest {

        @Test
        @DisplayName("returns true for matching secret")
        void validSecret() {
            var integration = IntegrationCredentials.builder()
                    .webhookSecret("test-secret-123")
                    .build();
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("test-secret-123");

            assertTrue(handler.validateWebhookRequest(integration, request));
        }

        @Test
        @DisplayName("returns false for wrong secret")
        void wrongSecret() {
            var integration = IntegrationCredentials.builder()
                    .webhookSecret("test-secret-123")
                    .build();
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("wrong-secret");

            assertFalse(handler.validateWebhookRequest(integration, request));
        }

        @Test
        @DisplayName("returns false when header is null")
        void nullHeader() {
            var integration = IntegrationCredentials.builder()
                    .webhookSecret("test-secret-123")
                    .build();
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn(null);

            assertFalse(handler.validateWebhookRequest(integration, request));
        }
    }

    @Nested
    @DisplayName("getPredefined*()")
    class Predefined {

        @Test
        @DisplayName("returns predefined triggers")
        void triggers() {
            var triggers = handler.getPredefinedTriggers();

            assertTrue(triggers.containsKey("telegram.message_received"));
            assertTrue(triggers.containsKey("telegram.photo_received"));
            assertTrue(triggers.containsKey("telegram.document_received"));
            assertTrue(triggers.containsKey("telegram.command_received"));
            assertTrue(triggers.containsKey("telegram.callback_query"));
        }

        @Test
        @DisplayName("returns predefined tools")
        void tools() {
            var tools = handler.getPredefinedTools();

            assertTrue(tools.containsKey("telegram.send_message"));
            assertTrue(tools.containsKey("telegram.send_photo"));
            assertTrue(tools.containsKey("telegram.edit_message"));
            assertTrue(tools.containsKey("telegram.delete_message"));
            assertTrue(tools.containsKey("telegram.answer_callback_query"));
        }
    }
}
