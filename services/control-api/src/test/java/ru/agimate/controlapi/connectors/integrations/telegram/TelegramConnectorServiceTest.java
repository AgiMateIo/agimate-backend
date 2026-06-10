package ru.agimate.controlapi.connectors.integrations.telegram;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramConnectorService")
class TelegramConnectorServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String IDENTITY = UUID.randomUUID().toString();

    @Mock
    private TelegramApiClient telegramApiClient;

    @Mock
    private TriggerRouterService triggerRouterService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TelegramConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = newHandler("webhook");
    }

    private TelegramConnectorService newHandler(String mode) {
        return new TelegramConnectorService(
                new TelegramToolService(telegramApiClient, triggerRouterService),
                telegramApiClient, objectMapper, mode);
    }

    private static ConnectorContext context() {
        return new ConnectorContext(IDENTITY, USER_ID, null, Map.of("token", "token123"), null);
    }

    private static ConnectorContext webhookContext(String secret) {
        return new ConnectorContext(IDENTITY, USER_ID, null, Map.of(), secret);
    }

    @Nested
    @DisplayName("метаданные")
    class Metadata {

        @Test
        @DisplayName("connectorCode/connectorName")
        void codeAndName() {
            assertEquals("telegram", handler.connectorCode());
            assertEquals("Telegram", handler.connectorName());
        }

        @Test
        @DisplayName("getCredentialFields: код поля → название")
        void credentialFields() {
            assertEquals(Map.of("token", "Bot API token"), handler.getCredentialFields());
        }

        @Test
        @DisplayName("getTools содержит все пять тулов, но не long_poll")
        void tools() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            assertTrue(tools.containsKey("telegram.send_message"));
            assertTrue(tools.containsKey("telegram.send_photo"));
            assertTrue(tools.containsKey("telegram.edit_message"));
            assertTrue(tools.containsKey("telegram.delete_message"));
            assertTrue(tools.containsKey("telegram.answer_callback_query"));
            assertFalse(tools.containsKey(TelegramToolService.TASK_LONG_POLL));

            ConnectorToolSpec sendMessage = tools.get("telegram.send_message");
            assertEquals("Send a text message", sendMessage.description());
            assertNotNull(sendMessage.inputSchema());
            assertFalse(sendMessage.annotations().destructiveHint());
        }

        @Test
        @DisplayName("getTriggers содержит все типы триггеров")
        void triggers() {
            var triggers = handler.getTriggers();

            assertTrue(triggers.containsKey("telegram.message_received"));
            assertTrue(triggers.containsKey("telegram.photo_received"));
            assertTrue(triggers.containsKey("telegram.document_received"));
            assertTrue(triggers.containsKey("telegram.command_received"));
            assertTrue(triggers.containsKey("telegram.callback_query"));
            assertEquals("Text message received", triggers.get("telegram.message_received").description());
        }
    }

    @Nested
    @DisplayName("режимы webhook/polling")
    class Modes {

        @Test
        @DisplayName("webhook-режим: supportsWebhooks=true, тасок нет")
        void webhookMode() {
            assertTrue(handler.supportsWebhooks());
            assertTrue(handler.getTasks().isEmpty());
        }

        @Test
        @DisplayName("polling-режим: supportsWebhooks=false, есть long_poll таска")
        void pollingMode() {
            TelegramConnectorService polling = newHandler("polling");

            assertFalse(polling.supportsWebhooks());
            var spec = polling.getTasks().get(TelegramToolService.TASK_LONG_POLL);
            assertNotNull(spec);
            assertEquals(0L, spec.taskConfig().get("intervalSeconds"));
            assertEquals(60, spec.timeoutSeconds());
        }
    }

    @Nested
    @DisplayName("validateCredentials")
    class ValidateCredentials {

        @Test
        @DisplayName("валидный токен")
        void validToken() {
            when(telegramApiClient.getMe("valid-token")).thenReturn(
                    Map.of("ok", true, "result", Map.of("username", "test_bot", "first_name", "Test Bot")));

            var result = handler.validateCredentials(Map.of("token", "valid-token"));

            assertTrue(result.valid());
            assertEquals("test_bot", result.identifier());
            assertEquals("Telegram: @test_bot", result.displayName());
        }

        @Test
        @DisplayName("невалидный токен — без утечки деталей исключения")
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
    @DisplayName("validateWebhookRequest")
    class ValidateWebhookRequest {

        @Test
        @DisplayName("совпадающий секрет")
        void validSecret() {
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("test-secret-123");

            assertTrue(handler.validateWebhookRequest(webhookContext("test-secret-123"), request));
        }

        @Test
        @DisplayName("неверный секрет")
        void wrongSecret() {
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("wrong-secret");

            assertFalse(handler.validateWebhookRequest(webhookContext("test-secret-123"), request));
        }

        @Test
        @DisplayName("отсутствующий заголовок")
        void nullHeader() {
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn(null);

            assertFalse(handler.validateWebhookRequest(webhookContext("test-secret-123"), request));
        }

        @Test
        @DisplayName("секрет не задан в контексте")
        void nullSecret() {
            var request = mock(HttpServletRequest.class);
            when(request.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("anything");

            assertFalse(handler.validateWebhookRequest(webhookContext(null), request));
        }
    }

    @Nested
    @DisplayName("normalizeInbound")
    class NormalizeInbound {

        @Test
        @DisplayName("парсит сырое webhook-тело и нормализует в триггер")
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

            Trigger result = handler.normalizeInbound(webhookContext(null), rawBody);

            assertEquals("telegram.message_received", result.name());
            assertEquals(IDENTITY, result.identity());
            assertEquals("Hello world", result.data().get("text"));
        }
    }

    @Nested
    @DisplayName("executeTool")
    class ExecuteTool {

        @Test
        @DisplayName("send_message: маппинг аргументов и токен из контекста")
        void sendMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true, "result", Map.of("message_id", 1));
            when(telegramApiClient.sendRequest(eq("sendMessage"), eq("token123"), any())).thenReturn(expectedResponse);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chatId", "100");
            params.put("text", "Hello");

            var result = handler.executeTool(context(), "telegram.send_message", params);

            assertEquals(expectedResponse, result);
            verify(telegramApiClient).sendRequest(eq("sendMessage"), eq("token123"), argThat((Map<String, Object> p) ->
                    p.get("chat_id").equals("100") && p.get("text").equals("Hello")));
        }

        @Test
        @DisplayName("send_message: длинный текст бьётся на чанки, reply — на первом, markup — на последнем")
        void sendMessageChunks() {
            when(telegramApiClient.sendRequest(eq("sendMessage"), eq("token123"), any()))
                    .thenReturn(Map.of("ok", true));

            String longText = "a".repeat(TelegramUtils.MAX_MESSAGE_LENGTH) + "\n" + "b".repeat(10);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chatId", "100");
            params.put("text", longText);
            params.put("replyToMessageId", "777");
            params.put("replyMarkup", "{\"keyboard\":[]}");

            handler.executeTool(context(), "telegram.send_message", params);

            verify(telegramApiClient).sendRequest(eq("sendMessage"), eq("token123"), argThat((Map<String, Object> p) ->
                    "777".equals(p.get("reply_to_message_id")) && !p.containsKey("reply_markup")));
            verify(telegramApiClient).sendRequest(eq("sendMessage"), eq("token123"), argThat((Map<String, Object> p) ->
                    "{\"keyboard\":[]}".equals(p.get("reply_markup")) && !p.containsKey("reply_to_message_id")));
        }

        @Test
        @DisplayName("edit_message")
        void editMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.sendRequest(eq("editMessageText"), eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(context(), "telegram.edit_message",
                    Map.of("chatId", "100", "messageId", "456", "text", "Updated"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("delete_message")
        void deleteMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.sendRequest(eq("deleteMessage"), eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(context(),
                    "telegram.delete_message", Map.of("chatId", "100", "messageId", "456"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("answer_callback_query")
        void answerCallbackQuery() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.sendRequest(eq("answerCallbackQuery"), eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(context(),
                    "telegram.answer_callback_query", Map.of("callbackQueryId", "cb1", "text", "Done"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("неизвестная тула → ConnectorException")
        void unknownTool() {
            assertThrows(ConnectorException.class, () ->
                    handler.executeTool(context(), "telegram.unknown_tool", Map.of()));
        }

        @Test
        @DisplayName("long_poll недоступен как тула")
        void longPollNotATool() {
            assertThrows(ConnectorException.class, () ->
                    handler.executeTool(context(), TelegramToolService.TASK_LONG_POLL, Map.of()));
        }
    }

    @Nested
    @DisplayName("executeTask: long_poll")
    class LongPoll {

        private Map<String, Object> update(long updateId, String text) {
            return Map.of(
                    "update_id", updateId,
                    "message", Map.of(
                            "message_id", 1,
                            "chat", Map.of("id", 100),
                            "text", text));
        }

        @Test
        @DisplayName("снимает webhook один раз, двигает offset, диспатчит триггеры")
        void happyPath() {
            when(telegramApiClient.getUpdates("token123", null, 20))
                    .thenReturn(Map.of("ok", true, "result", List.of(update(5, "hi"))));
            when(telegramApiClient.getUpdates("token123", 6L, 20))
                    .thenReturn(Map.of("ok", true, "result", List.of()));

            handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of());
            handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of());

            verify(telegramApiClient, times(1)).deleteWebhook("token123");
            verify(telegramApiClient).getUpdates("token123", 6L, 20);
            verify(triggerRouterService).routeWhTrigger(eq(USER_ID), argThat((Trigger t) ->
                    "telegram.message_received".equals(t.name()) && IDENTITY.equals(t.identity())));
        }

        @Test
        @DisplayName("409 Conflict → ConnectorException без cause с токеном")
        void conflict() {
            when(telegramApiClient.getUpdates("token123", null, 20))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], null));

            ConnectorException e = assertThrows(ConnectorException.class, () ->
                    handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of()));

            assertNull(e.getCause());
            assertTrue(e.getMessage().contains("409"));
        }

        @Test
        @DisplayName("ответ не ok → ConnectorException")
        void notOkResponse() {
            when(telegramApiClient.getUpdates("token123", null, 20))
                    .thenReturn(Map.of("ok", false, "description", "Unauthorized"));

            ConnectorException e = assertThrows(ConnectorException.class, () ->
                    handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of()));

            assertTrue(e.getMessage().contains("Unauthorized"));
        }

        @Test
        @DisplayName("пустые credentials → ConnectorException")
        void missingToken() {
            ConnectorContext noToken = new ConnectorContext(IDENTITY, USER_ID, null, Map.of(), null);

            assertThrows(ConnectorException.class, () ->
                    handler.executeTask(noToken, TelegramToolService.TASK_LONG_POLL, Map.of()));
        }

        @Test
        @DisplayName("ошибка deleteWebhook не блокирует поллинг и ретраится на следующем тике")
        void deleteWebhookRetries() {
            when(telegramApiClient.deleteWebhook("token123"))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(Map.of("ok", true));
            when(telegramApiClient.getUpdates(eq("token123"), isNull(), eq(20)))
                    .thenReturn(Map.of("ok", true, "result", List.of()));

            handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of());
            handler.executeTask(context(), TelegramToolService.TASK_LONG_POLL, Map.of());

            verify(telegramApiClient, times(2)).deleteWebhook("token123");
        }
    }
}
