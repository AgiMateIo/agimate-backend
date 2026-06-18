package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тулы и таски Telegram-коннектора. Контекст (credentials, identity, userId) приходит через
 * {@link ConnectorContextHolder}, привязку делает {@code BaseConnectorHandler} в
 * {@code TelegramConnectorService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramToolService {

    public static final String TASK_LONG_POLL = "telegram.long_poll";
    private static final int LONG_POLL_TIMEOUT_SEC = 20;

    private final TelegramApiClient telegramApiClient;
    private final TriggerRouterService triggerRouterService;

    /**
     * Per‑integration cache long‑poll'а (ключ — identity, т.е. id integration_credentials):
     *   {@code offsets} — следующий update_id, передаваемый в getUpdates;
     *   {@code webhookDeleted} — флаг «уже вызывали deleteWebhook» (Telegram не любит держать
     *   и webhook, и getUpdates одновременно — 409 Conflict).
     * Состояние live до рестарта процесса. Восстанавливать после рестарта необязательно:
     * Telegram сам отдаст неподтверждённые updates на запрос без offset.
     */
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    private final Set<String> webhookDeleted = ConcurrentHashMap.newKeySet();

    @Tool(name = "telegram.send_message", description = "Send a text message",
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

    @Tool(name = "telegram.send_photo", description = "Send a photo",
            annotations = @ToolAnnotations(destructiveHint = false))
    public Map<String, Object> toolSendPhoto(
            @ToolParam("Target chat ID") String chatId,
            @ToolParam("Photo URL or file ID") String photo,
            @ToolParam(value = "Photo caption", required = false) String caption) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("photo", photo);
        if (caption != null) apiParams.put("caption", TelegramUtils.truncate(caption, TelegramUtils.MAX_CAPTION_LENGTH));
        return sendTelegramRequest("sendPhoto", apiParams);
    }

    // edit_message перезаписывает прежний текст → destructiveHint=true (дефолт).
    @Tool(name = "telegram.edit_message", description = "Edit a message")
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

    @Tool(name = "telegram.delete_message", description = "Delete a message")
    public Map<String, Object> toolDeleteMessage(
            @ToolParam("Chat ID") String chatId,
            @ToolParam("Message ID to delete") String messageId) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        return sendTelegramRequest("deleteMessage", apiParams);
    }

    @Tool(name = "telegram.answer_callback_query", description = "Answer a callback query",
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
    @Job(intervalSeconds = 0, timeoutSeconds = 60)
    @SuppressWarnings("unchecked")
    public void longPoll() {
        ConnectorContext ctx = ConnectorContextHolder.current();
        String identity = ctx.identity();
        String token = ctx.credentials().get("token");
        if (token == null || token.isBlank()) {
            throw new ConnectorException("Integration " + identity + " has no telegram token");
        }

        if (webhookDeleted.add(identity)) {
            try {
                telegramApiClient.deleteWebhook(token);
            } catch (Exception e) {
                // Не логируем e.getMessage() / не передаём cause: Spring RestClient может вложить
                // URL вида /bot{token}/... в текст или стек, что утечёт токен в логи.
                log.warn("Failed to deleteWebhook before polling for {}: {}",
                        identity, e.getClass().getSimpleName());
                webhookDeleted.remove(identity); // дать шанс ретраю на следующем tick'е
            }
        }

        Long offset = offsets.get(identity);
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
                offsets.put(identity, updateId.longValue() + 1);
            }
            dispatch(ctx, update);
        }
    }

    private void dispatch(ConnectorContext ctx, Map<String, Object> update) {
        try {
            Trigger trigger = TelegramUtils.normalizeUpdate(update, ctx.identity());
            triggerRouterService.routeWhTrigger(ctx.userId(), trigger);
        } catch (Exception e) {
            log.error("Failed to dispatch update for integration {}: {}",
                    ctx.identity(), e.getMessage());
        }
    }

    private Map<String, Object> sendTelegramRequest(String method, Map<String, Object> params) {
        String token = ConnectorContextHolder.current().credentials().get("token");
        return telegramApiClient.sendRequest(method, token, params);
    }
}
