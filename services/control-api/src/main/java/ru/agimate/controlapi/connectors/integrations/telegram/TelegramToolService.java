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
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileNames;
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
 * Tools and jobs of the Telegram connector. The context (credentials, connectionId, userId) arrives
 * through {@link ConnectorEnvHolder}, and the binding is done by {@code BaseConnectorHandler} in
 * {@code TelegramConnectorService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramToolService {

    public static final String TASK_LONG_POLL = "long_poll";
    private static final int LONG_POLL_TIMEOUT_SEC = 20;

    /** The Bot API's limit on a bot's file upload. Our own check — we do not rely on app.files.max-file-size-bytes. */
    private static final long BOT_UPLOAD_LIMIT_BYTES = 50L * 1024 * 1024;

    private final TelegramApiClient telegramApiClient;
    private final TriggerRouterService triggerRouterService;
    private final FileStorageService fileStorageService;
    private final TelegramMediaService mediaService;

    /**
     * Per-integration long-poll cache (keyed by connectionId, i.e. {@code connections.id}):
     *   {@code offsets} — the next update_id passed into getUpdates;
     *   {@code webhookDeleted} — the «deleteWebhook has already been called» flag (Telegram dislikes
     *   holding both a webhook and getUpdates at once — 409 Conflict).
     * The state lives until the process restarts. Restoring it afterwards is unnecessary: Telegram
     * itself hands back the unacknowledged updates on a request with no offset.
     */
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    private final Set<String> webhookDeleted = ConcurrentHashMap.newKeySet();

    @Tool(name = "send_message", description = "Send a text message",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = true))
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
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = true))
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
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = true))
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
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = true))
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
     * Sending media: an {@code agf_…} means bytes from the file layer as multipart (ownership is
     * checked by {@link FileStorageService#open} against the userId from the env), otherwise the value
     * goes out as-is (a URL or a Telegram file_id) in an ordinary JSON call.
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
                long sizeBytes = file.file().getSizeBytes();
                if (sizeBytes > BOT_UPLOAD_LIMIT_BYTES) {
                    throw new ConnectorException("file " + value + " is too large for Telegram bot upload: "
                            + sizeBytes + " bytes, limit " + BOT_UPLOAD_LIMIT_BYTES + " (50 MB)");
                }
                // What the agent asked for → what FileNames would call the file when downloading it
                // (the stored name, else a synthetic one): a document forwarded to a chat should keep
                // the name the user sent it with.
                String effectiveName = fileName != null && !fileName.isBlank() ? fileName
                        : FileNames.forDownload(FileLink.of(file.file()));
                return telegramApiClient.sendRequestMultipart(method, token, apiParams, field,
                        effectiveName, file.file().getMime(), content, file.file().getSizeBytes());
            }
        } catch (FileStorageException e) {
            // The message goes to the agent: «file not found: agf_…» / the storage's reason for refusal.
            throw new ConnectorException(e.getMessage());
        } catch (IOException e) {
            throw new ConnectorException("Failed to read file " + value + ": " + e.getClass().getSimpleName());
        }
    }

    // edit_message overwrites the previous text → destructiveHint=true (the default).
    @Tool(name = "edit_message", description = "Edit a message",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = true))
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

    @Tool(name = "delete_message", description = "Delete a message",
            annotations = @ToolAnnotations(destructiveHint = true, idempotentHint = true,
                    openWorldHint = true))
    public Map<String, Object> toolDeleteMessage(
            @ToolParam("Chat ID") String chatId,
            @ToolParam("Message ID to delete") String messageId) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        return sendTelegramRequest("deleteMessage", apiParams);
    }

    @Tool(name = "answer_callback_query", description = "Answer a callback query",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = true))
    public Map<String, Object> toolAnswerCallbackQuery(
            @ToolParam("Callback query ID") String callbackQueryId,
            @ToolParam(value = "Response text", required = false) String text) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("callback_query_id", callbackQueryId);
        if (text != null) apiParams.put("text", text);
        return sendTelegramRequest("answerCallbackQuery", apiParams);
    }

    /**
     * One iteration of long polling: remove the webhook from Telegram if needed, do a
     * {@code getUpdates(timeout=20s)}, dispatch the received updates through
     * {@link TriggerRouterService} and update the offset in memory.
     *
     * <p>{@code intervalSeconds = 0} — the scheduler sets {@code next_run_at = now} and picks the row
     * up on the next tick (≤1s); one run = one getUpdates. On failure —
     * {@code next_run_at = now + 60s} (the shared error retry).
     */
    @Tool(name = TASK_LONG_POLL, description = "Long-poll Telegram updates and dispatch them as triggers")
    // timeoutSeconds is the claim's lease: an iteration takes ~20s, and a short lease bounds the polling
    // pause after an abrupt restart (kill -9), when the graceful release never ran.
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
                // We do not log e.getMessage() and do not pass the cause on: Spring RestClient may embed a URL
                // of the form /bot{token}/... into the text or the stack, which would leak the token into the logs.
                log.warn("Failed to deleteWebhook before polling for {}: {}",
                        connectionId, e.getClass().getSimpleName());
                webhookDeleted.remove(connectionId); // give the retry a chance on the next tick
            }
        }

        Long offset = offsets.get(connectionId);
        Map<String, Object> response;
        try {
            response = telegramApiClient.getUpdates(token, offset, LONG_POLL_TIMEOUT_SEC);
        } catch (HttpClientErrorException.Conflict e) {
            // Another process is holding getUpdates with this token — let the scheduler wait 60s.
            // The cause is not propagated — see the comment above about leaking a URL containing the token.
            throw new ConnectorException(
                    "Telegram 409 Conflict — another process holds long-poll for this bot");
        } catch (Exception e) {
            // Any other transport error from Telegram: the exception's class only, with no message or cause.
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
            // The token is already decrypted in the long poll's env — we materialise the media before routing.
            trigger = mediaService.materialize(ctx.credentials().get("token"), ctx.userId(),
                    ctx.connectionId(), trigger);
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
