package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageException;
import ru.agimate.controlapi.storage.FileStorageService;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тулы и таски Telegram-коннектора. Контекст (credentials, connectionId, userId) приходит через
 * {@link ConnectorEnvHolder}, привязку делает {@code BaseConnectorHandler} в
 * {@code TelegramConnectorService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramToolService {

    public static final String TASK_LONG_POLL = "long_poll";
    private static final int LONG_POLL_TIMEOUT_SEC = 20;

    private final TelegramApiClient telegramApiClient;
    private final TriggerRouterService triggerRouterService;
    private final FileStorageService fileStorageService;

    /**
     * Per‑integration cache long‑poll'а (ключ — connectionId, т.е. {@code connections.id}):
     *   {@code offsets} — следующий update_id, передаваемый в getUpdates;
     *   {@code webhookDeleted} — флаг «уже вызывали deleteWebhook» (Telegram не любит держать
     *   и webhook, и getUpdates одновременно — 409 Conflict).
     * Состояние live до рестарта процесса. Восстанавливать после рестарта необязательно:
     * Telegram сам отдаст неподтверждённые updates на запрос без offset.
     */
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    private final Set<String> webhookDeleted = ConcurrentHashMap.newKeySet();

    @Tool(name = "send_message", description = "Send a text message",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolSendMessage(
            @ToolParam("Target chat ID") String chatId,
            @ToolParam("Message text") String text,
            @ToolParam(value = "Parse mode (HTML, Markdown, MarkdownV2)", required = false) String parseMode,
            @ToolParam(value = "ID of message to reply to", required = false) String replyToMessageId,
            @ToolParam(value = "Inline keyboard markup as JSON", required = false) String replyMarkup) {
        List<String> chunks = TelegramUtils.splitMessage(text == null ? "" : text, TelegramUtils.MAX_MESSAGE_LENGTH);
        Map<String, Object> lastResponse = null;
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> apiParams = new LinkedHashMap<>();
            apiParams.put("chat_id", chatId);
            apiParams.put("text", chunks.get(i));
            if (parseMode != null) apiParams.put("parse_mode", parseMode);
            // Reply only to the first chunk; the inline keyboard belongs on the last one.
            if (replyToMessageId != null && i == 0) apiParams.put("reply_to_message_id", replyToMessageId);
            if (replyMarkup != null && i == chunks.size() - 1) apiParams.put("reply_markup", replyMarkup);
            lastResponse = sendTelegramRequest("sendMessage", apiParams);
        }
        return lastResponse;
    }

    @Tool(name = "send_photo", description = "Send a photo",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolSendPhoto(
            @ToolParam("Target chat ID") String chatId,
            @ToolParam("Photo: URL, Telegram file_id, or agimate file id (agf_… from a tool result)")
            String photo,
            @ToolParam(value = "Photo caption", required = false) String caption) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        if (caption != null) apiParams.put("caption", TelegramUtils.truncate(caption, TelegramUtils.MAX_CAPTION_LENGTH));
        return sendMedia("sendPhoto", "photo", photo, null, apiParams);
    }

    @Tool(name = "send_document", description = "Send a document (file)",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolSendDocument(
            @ToolParam("Target chat ID") String chatId,
            @ToolParam("Document: URL, Telegram file_id, or agimate file id (agf_… from a tool result)")
            String document,
            @ToolParam(value = "Document caption", required = false) String caption,
            @ToolParam(value = "Display file name (agimate files only)", required = false) String fileName) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        if (caption != null) apiParams.put("caption", TelegramUtils.truncate(caption, TelegramUtils.MAX_CAPTION_LENGTH));
        return sendMedia("sendDocument", "document", document, fileName, apiParams);
    }

    @Tool(name = "send_video", description = "Send a video",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolSendVideo(
            @ToolParam("Target chat ID") String chatId,
            @ToolParam("Video: URL, Telegram file_id, or agimate file id (agf_… from a tool result)")
            String video,
            @ToolParam(value = "Video caption", required = false) String caption) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        if (caption != null) apiParams.put("caption", TelegramUtils.truncate(caption, TelegramUtils.MAX_CAPTION_LENGTH));
        return sendMedia("sendVideo", "video", video, null, apiParams);
    }

    /**
     * Отправка медиа: {@code agf_…} — байты из файлового слоя multipart'ом (владение проверяет
     * {@link FileStorageService#open} по userId из env), иначе значение уходит как есть
     * (URL / Telegram file_id) обычным JSON-вызовом.
     */
    private Map<String, Object> sendMedia(String method, String field, String value,
                                          String fileName, Map<String, Object> apiParams) {
        ConnectorEnv env = ConnectorEnvHolder.current();
        String token = env.credentials().get("token");
        if (FileIds.parse(value).isEmpty()) {
            apiParams.put(field, value);
            return telegramApiClient.sendRequest(method, token, apiParams);
        }
        try {
            FileStorageService.FileContent file = fileStorageService.open(env.userId(), value);
            try (InputStream content = file.content()) {
                String effectiveName = fileName != null && !fileName.isBlank()
                        ? fileName : defaultFilename(value, file.file().getMime());
                return telegramApiClient.sendRequestMultipart(method, token, apiParams, field,
                        effectiveName, file.file().getMime(), content, file.file().getSizeBytes());
            }
        } catch (FileStorageException e) {
            // Сообщение уходит агенту: «file not found: agf_…» / причина отказа хранилища.
            throw new ConnectorException(e.getMessage());
        } catch (IOException e) {
            throw new ConnectorException("Failed to read file " + value + ": " + e.getClass().getSimpleName());
        }
    }

    /** Имя файла для multipart-части: Telegram показывает его в чате для документов. */
    private static String defaultFilename(String fileId, String mime) {
        String subtype = mime != null && mime.contains("/")
                ? mime.substring(mime.indexOf('/') + 1) : "bin";
        // "svg+xml" и т.п. → берём часть до '+'; неалфавитные хвосты не годятся в расширение
        int plus = subtype.indexOf('+');
        if (plus > 0) {
            subtype = subtype.substring(0, plus);
        }
        return fileId + "." + subtype;
    }

    // edit_message перезаписывает прежний текст → destructiveHint=true (дефолт).
    @Tool(name = "edit_message", description = "Edit a message")
    public Map<String, Object> toolEditMessage(
            @ToolParam("Chat ID") String chatId,
            @ToolParam("Message ID to edit") String messageId,
            @ToolParam("New message text") String text) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        apiParams.put("text", TelegramUtils.truncate(text, TelegramUtils.MAX_MESSAGE_LENGTH));
        return sendTelegramRequest("editMessageText", apiParams);
    }

    @Tool(name = "delete_message", description = "Delete a message")
    public Map<String, Object> toolDeleteMessage(
            @ToolParam("Chat ID") String chatId,
            @ToolParam("Message ID to delete") String messageId) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        return sendTelegramRequest("deleteMessage", apiParams);
    }

    @Tool(name = "answer_callback_query", description = "Answer a callback query",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolAnswerCallbackQuery(
            @ToolParam("Callback query ID") String callbackQueryId,
            @ToolParam(value = "Response text", required = false) String text) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("callback_query_id", callbackQueryId);
        if (text != null) apiParams.put("text", text);
        return sendTelegramRequest("answerCallbackQuery", apiParams);
    }

    /**
     * Одна итерация long‑polling'а: при необходимости снимаем webhook у Telegram, делаем
     * {@code getUpdates(timeout=20s)}, диспатчим полученные обновления через
     * {@link TriggerRouterService}, обновляем offset в памяти.
     *
     * <p>{@code intervalSeconds = 0} — scheduler ставит {@code next_run_at = now} и подхватывает
     * строку на следующем тике (≤1с); один запуск = один getUpdates. На ошибке —
     * {@code next_run_at = now + 60s} (общий error retry).
     */
    @Tool(name = TASK_LONG_POLL, description = "Long-poll Telegram updates and dispatch them as triggers")
    // timeoutSeconds — это lease claim'а: итерация ~20s, короткий lease ограничивает паузу
    // поллинга после аварийного рестарта (kill -9), когда graceful release не отработал.
    @Job(intervalSeconds = 0, timeoutSeconds = 30)
    @SuppressWarnings("unchecked")
    public void longPoll() {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        String connectionId = ctx.connectionId();
        String token = ctx.credentials().get("token");
        if (token == null || token.isBlank()) {
            throw new ConnectorException("Integration " + connectionId + " has no telegram token");
        }

        if (webhookDeleted.add(connectionId)) {
            try {
                telegramApiClient.deleteWebhook(token);
            } catch (Exception e) {
                // Не логируем e.getMessage() / не передаём cause: Spring RestClient может вложить
                // URL вида /bot{token}/... в текст или стек, что утечёт токен в логи.
                log.warn("Failed to deleteWebhook before polling for {}: {}",
                        connectionId, e.getClass().getSimpleName());
                webhookDeleted.remove(connectionId); // дать шанс ретраю на следующем tick'е
            }
        }

        Long offset = offsets.get(connectionId);
        Map<String, Object> response;
        try {
            response = telegramApiClient.getUpdates(token, offset, LONG_POLL_TIMEOUT_SEC);
        } catch (HttpClientErrorException.Conflict e) {
            // Другой процесс держит getUpdates с этим токеном — пусть scheduler подождёт 60s.
            // Cause не пробрасываем — см. комментарий выше про утечку URL c токеном.
            throw new ConnectorException(
                    "Telegram 409 Conflict — another process holds long-poll for this bot");
        } catch (Exception e) {
            // Любая другая transport-ошибка телеги: только класс исключения, без message/cause.
            throw new ConnectorException(
                    "Telegram getUpdates failed: " + e.getClass().getSimpleName());
        }
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            throw new ConnectorException("Telegram getUpdates failed: " + response.get("description"));
        }

        List<Map<String, Object>> updates = (List<Map<String, Object>>) response.get("result");
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (Map<String, Object> update : updates) {
            Number updateId = (Number) update.get("update_id");
            if (updateId != null) {
                offsets.put(connectionId, updateId.longValue() + 1);
            }
            dispatch(ctx, update);
        }
    }

    private void dispatch(ConnectorEnv ctx, Map<String, Object> update) {
        try {
            Trigger trigger = TelegramUtils.normalizeUpdate(update, ctx.connectionId());
            triggerRouterService.routeWhTrigger(ctx.userId(), trigger);
        } catch (Exception e) {
            log.error("Failed to dispatch update for integration {}: {}",
                    ctx.connectionId(), e.getMessage());
        }
    }

    private Map<String, Object> sendTelegramRequest(String method, Map<String, Object> params) {
        String token = ConnectorEnvHolder.current().credentials().get("token");
        return telegramApiClient.sendRequest(method, token, params);
    }
}
