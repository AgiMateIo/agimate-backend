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
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Mock
    private FileStorageService fileStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TelegramConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = newHandler("webhook");
    }

    private TelegramConnectorService newHandler(String mode) {
        return new TelegramConnectorService(
                new TelegramToolService(telegramApiClient, triggerRouterService, fileStorageService),
                telegramApiClient, objectMapper, mode);
    }

    private static ConnectorEnv env() {
        return new ConnectorEnv(IDENTITY, USER_ID, null, null, null, Map.of("token", "token123"), null);
    }

    private static ConnectorEnv webhookContext(String secret) {
        return new ConnectorEnv(IDENTITY, USER_ID, null, null, null, Map.of(), secret);
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
        @DisplayName("getTools содержит все семь тулов, но не long_poll")
        void tools() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            assertTrue(tools.containsKey("send_message"));
            assertTrue(tools.containsKey("send_photo"));
            assertTrue(tools.containsKey("send_document"));
            assertTrue(tools.containsKey("send_video"));
            assertTrue(tools.containsKey("edit_message"));
            assertTrue(tools.containsKey("delete_message"));
            assertTrue(tools.containsKey("answer_callback_query"));
            assertFalse(tools.containsKey(TelegramToolService.TASK_LONG_POLL));

            ConnectorToolSpec sendMessage = tools.get("send_message");
            assertEquals("Send a text message", sendMessage.description());
            assertNotNull(sendMessage.inputSchema());
            assertFalse(sendMessage.annotations().destructiveHint());
        }

        @Test
        @DisplayName("getTriggers содержит все типы триггеров")
        void triggers() {
            var triggers = handler.getTriggers();

            assertTrue(triggers.containsKey("message_received"));
            assertTrue(triggers.containsKey("photo_received"));
            assertTrue(triggers.containsKey("document_received"));
            assertTrue(triggers.containsKey("command_received"));
            assertTrue(triggers.containsKey("callback_query"));
            assertEquals("Text message received", triggers.get("message_received").description());
        }
    }

    @Nested
    @DisplayName("режимы webhook/polling")
    class Modes {

        @Test
        @DisplayName("webhook-режим: supportsWebhooks=true, тасок нет")
        void webhookMode() {
            assertTrue(handler.supportsWebhooks());
            assertTrue(handler.getJobs().isEmpty());
        }

        @Test
        @DisplayName("polling-режим: supportsWebhooks=false, есть long_poll таска")
        void pollingMode() {
            TelegramConnectorService polling = newHandler("polling");

            assertFalse(polling.supportsWebhooks());
            var spec = polling.getJobs().get(TelegramToolService.TASK_LONG_POLL);
            assertNotNull(spec);
            assertEquals(0L, spec.config().get("intervalSeconds"));
            assertEquals(30, spec.timeoutSeconds());
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

            assertEquals("message_received", result.name());
            assertEquals(IDENTITY, result.connectionId());
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

            var result = handler.executeTool(env(), "send_message", params);

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

            handler.executeTool(env(), "send_message", params);

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

            var result = handler.executeTool(env(), "edit_message",
                    Map.of("chatId", "100", "messageId", "456", "text", "Updated"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("delete_message")
        void deleteMessage() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.sendRequest(eq("deleteMessage"), eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(env(),
                    "delete_message", Map.of("chatId", "100", "messageId", "456"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("answer_callback_query")
        void answerCallbackQuery() {
            Map<String, Object> expectedResponse = Map.of("ok", true);
            when(telegramApiClient.sendRequest(eq("answerCallbackQuery"), eq("token123"), any())).thenReturn(expectedResponse);

            var result = handler.executeTool(env(),
                    "answer_callback_query", Map.of("callbackQueryId", "cb1", "text", "Done"));

            assertEquals(expectedResponse, result);
        }

        @Test
        @DisplayName("send_photo с URL — обычный JSON-вызов, без файлового слоя")
        void sendPhotoWithUrl() {
            when(telegramApiClient.sendRequest(eq("sendPhoto"), eq("token123"), any()))
                    .thenReturn(Map.of("ok", true));

            handler.executeTool(env(), "send_photo",
                    Map.of("chatId", "100", "photo", "https://example.com/cat.png"));

            verify(telegramApiClient).sendRequest(eq("sendPhoto"), eq("token123"),
                    argThat((Map<String, Object> p) -> "https://example.com/cat.png".equals(p.get("photo"))));
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("send_photo с agf_-id — байты из файлового слоя multipart'ом, ownership по env")
        void sendPhotoWithFileRef() {
            StoredFile stored = StoredFile.builder()
                    .id(UUID.randomUUID()).userId(USER_ID).status(FileStatus.READY)
                    .mime("image/png").sizeBytes(5L)
                    .expiresAt(LocalDateTime.now().plusDays(1)).build();
            String fileId = FileIds.external(stored.getId());
            when(fileStorageService.open(USER_ID, fileId)).thenReturn(new FileStorageService.FileContent(
                    stored, new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8))));
            when(telegramApiClient.sendRequestMultipart(eq("sendPhoto"), eq("token123"), any(),
                    eq("photo"), eq(fileId + ".png"), eq("image/png"), any(), eq(5L)))
                    .thenReturn(Map.of("ok", true));

            var result = handler.executeTool(env(), "send_photo",
                    Map.of("chatId", "100", "photo", fileId, "caption", "screenshot"));

            assertEquals(Map.of("ok", true), result);
            verify(telegramApiClient).sendRequestMultipart(eq("sendPhoto"), eq("token123"),
                    argThat((Map<String, Object> p) ->
                            "100".equals(p.get("chat_id")) && "screenshot".equals(p.get("caption"))
                                    && !p.containsKey("photo")),
                    eq("photo"), eq(fileId + ".png"), eq("image/png"), any(), eq(5L));
        }

        @Test
        @DisplayName("send_document с agf_-id и fileName — имя части берётся из fileName")
        void sendDocumentWithFileName() {
            StoredFile stored = StoredFile.builder()
                    .id(UUID.randomUUID()).userId(USER_ID).status(FileStatus.READY)
                    .mime("application/pdf").sizeBytes(3L)
                    .expiresAt(LocalDateTime.now().plusDays(1)).build();
            String fileId = FileIds.external(stored.getId());
            when(fileStorageService.open(USER_ID, fileId)).thenReturn(new FileStorageService.FileContent(
                    stored, new ByteArrayInputStream(new byte[]{1, 2, 3})));
            when(telegramApiClient.sendRequestMultipart(any(), any(), any(), any(), any(), any(), any(), anyLong()))
                    .thenReturn(Map.of("ok", true));

            handler.executeTool(env(), "send_document",
                    Map.of("chatId", "100", "document", fileId, "fileName", "report.pdf"));

            verify(telegramApiClient).sendRequestMultipart(eq("sendDocument"), eq("token123"), any(),
                    eq("document"), eq("report.pdf"), eq("application/pdf"), any(), eq(3L));
        }

        @Test
        @DisplayName("неизвестный/чужой agf_-id → ConnectorException с причиной для агента")
        void sendPhotoWithUnknownFileRef() {
            String fileId = FileIds.external(UUID.randomUUID());
            when(fileStorageService.open(USER_ID, fileId))
                    .thenThrow(new StoredFileNotFoundException(fileId));

            ConnectorException e = assertThrows(ConnectorException.class, () ->
                    handler.executeTool(env(), "send_photo", Map.of("chatId", "100", "photo", fileId)));

            assertTrue(e.getMessage().contains(fileId));
            verifyNoInteractions(telegramApiClient);
        }

        @Test
        @DisplayName("неизвестная тула → ConnectorException")
        void unknownTool() {
            assertThrows(ConnectorException.class, () ->
                    handler.executeTool(env(), "telegram.unknown_tool", Map.of()));
        }

        @Test
        @DisplayName("long_poll недоступен как тула")
        void longPollNotATool() {
            assertThrows(ConnectorException.class, () ->
                    handler.executeTool(env(), TelegramToolService.TASK_LONG_POLL, Map.of()));
        }
    }

    @Nested
    @DisplayName("executeJob: long_poll")
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

            handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of());
            handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of());

            verify(telegramApiClient, times(1)).deleteWebhook("token123");
            verify(telegramApiClient).getUpdates("token123", 6L, 20);
            verify(triggerRouterService).routeWhTrigger(eq(USER_ID), argThat((Trigger t) ->
                    "message_received".equals(t.name()) && IDENTITY.equals(t.connectionId())));
        }

        @Test
        @DisplayName("409 Conflict → ConnectorException без cause с токеном")
        void conflict() {
            when(telegramApiClient.getUpdates("token123", null, 20))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], null));

            ConnectorException e = assertThrows(ConnectorException.class, () ->
                    handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of()));

            assertNull(e.getCause());
            assertTrue(e.getMessage().contains("409"));
        }

        @Test
        @DisplayName("ответ не ok → ConnectorException")
        void notOkResponse() {
            when(telegramApiClient.getUpdates("token123", null, 20))
                    .thenReturn(Map.of("ok", false, "description", "Unauthorized"));

            ConnectorException e = assertThrows(ConnectorException.class, () ->
                    handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of()));

            assertTrue(e.getMessage().contains("Unauthorized"));
        }

        @Test
        @DisplayName("пустые credentials → ConnectorException")
        void missingToken() {
            ConnectorEnv noToken = new ConnectorEnv(IDENTITY, USER_ID, null, null, null, Map.of(), null);

            assertThrows(ConnectorException.class, () ->
                    handler.executeJob(noToken, TelegramToolService.TASK_LONG_POLL, Map.of()));
        }

        @Test
        @DisplayName("ошибка deleteWebhook не блокирует поллинг и ретраится на следующем тике")
        void deleteWebhookRetries() {
            when(telegramApiClient.deleteWebhook("token123"))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(Map.of("ok", true));
            when(telegramApiClient.getUpdates(eq("token123"), isNull(), eq(20)))
                    .thenReturn(Map.of("ok", true, "result", List.of()));

            handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of());
            handler.executeJob(env(), TelegramToolService.TASK_LONG_POLL, Map.of());

            verify(telegramApiClient, times(2)).deleteWebhook("token123");
        }
    }
}
