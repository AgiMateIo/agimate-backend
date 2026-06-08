package ru.agimate.controlapi.connectors.integrations.telegram;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ru.agimate.controlapi.connectors.integrations.BaseIntegrationHandler;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.tasks.TaskDescriptor;
import ru.agimate.controlapi.connectors.tasks.TaskScope;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TelegramHandler extends BaseIntegrationHandler {

    public static final String CONNECTOR_CODE = "telegram";
    public static final String MODE_WEBHOOK = "webhook";
    public static final String MODE_POLLING = "polling";
    public static final String TASK_LONG_POLL = "long-poll";
    private static final String HEADER_SECRET_TOKEN = "X-Telegram-Bot-Api-Secret-Token";
    private static final int LONG_POLL_TIMEOUT_SEC = 20;

    /** Telegram's hard limit for a single sendMessage/editMessageText text (UTF-16 code units, == String.length()). */
    static final int MAX_MESSAGE_LENGTH = 4096;

    /** Telegram's hard limit for a media caption (sendPhoto and friends). */
    static final int MAX_CAPTION_LENGTH = 1024;

    private final TelegramApiClient telegramApiClient;
    private final ObjectMapper objectMapper;
    private final TriggerRouterService triggerRouterService;
    private final String mode;

    /**
     * Per‑integration cache long‑poll'а:
     *   {@code offsets} — следующий update_id, передаваемый в getUpdates;
     *   {@code webhookDeleted} — флаг «уже вызывали deleteWebhook» (Telegram не любит держать
     *   и webhook, и getUpdates одновременно — 409 Conflict).
     * Состояние live до рестарта процесса. Восстанавливать после рестарта необязательно:
     * Telegram сам отдаст неподтверждённые updates на запрос без offset.
     */
    private final Map<UUID, Long> offsets = new ConcurrentHashMap<>();
    private final Set<UUID> webhookDeleted = ConcurrentHashMap.newKeySet();

    public TelegramHandler(IntegrationEncryptionService encryptionService,
                           TelegramApiClient telegramApiClient,
                           ObjectMapper objectMapper,
                           TriggerRouterService triggerRouterService,
                           @Value("${app.integration.telegram.mode:webhook}") String mode) {
        super(encryptionService);
        this.telegramApiClient = telegramApiClient;
        this.objectMapper = objectMapper;
        this.triggerRouterService = triggerRouterService;
        this.mode = mode;
    }

    @Override
    public String getConnectorCode() {
        return CONNECTOR_CODE;
    }

    public boolean isPollingMode() {
        return MODE_POLLING.equalsIgnoreCase(mode);
    }

    @Override
    public boolean supportsWebhooks() {
        return !isPollingMode();
    }

    @Override
    public List<String> getCredentialFields() {
        return List.of("token");
    }

    @Override
    public String getConnectorName() {
        return "Telegram";
    }

    @Override
    @SuppressWarnings("unchecked")
    public IntegrationValidationResult validateCredentials(Map<String, String> credentials) {
        String token = credentials.get("token");
        try {
            Map<String, Object> response = telegramApiClient.getMe(token);
            if (!Boolean.TRUE.equals(response.get("ok"))) {
                return IntegrationValidationResult.failure("token", "Telegram API returned error");
            }
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            String username = (String) result.get("username");
            String displayName = "Telegram: @" + username;
            return IntegrationValidationResult.success(username, displayName);
        } catch (Exception e) {
            log.warn("Failed to validate Telegram token", e);
            return IntegrationValidationResult.failure("token", "Failed to validate token");
        }
    }

    @Override
    public void setupWebhook(IntegrationCredentials integrationCredentials, Map<String, String> credentials, String webhookUrl) {
        String token = credentials.get("token");
        try {
            Map<String, Object> response = telegramApiClient.setWebhook(
                    token, webhookUrl, integrationCredentials.getWebhookSecret());
            if (!Boolean.TRUE.equals(response.get("ok"))) {
                throw new IllegalStateException("Failed to set Telegram webhook: " + response.get("description"));
            }
            log.info("Telegram webhook set to {}", webhookUrl);
        } catch (Exception e) {
            log.error("Failed to set Telegram webhook: {}", e.getMessage());
            throw new IllegalStateException("Failed to set Telegram webhook", e);
        }
    }

    @Override
    public void removeWebhook(Map<String, String> credentials) {
        String token = credentials.get("token");
        try {
            telegramApiClient.deleteWebhook(token);
            log.info("Telegram webhook removed");
        } catch (Exception e) {
            log.warn("Failed to remove Telegram webhook: {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Trigger normalizeInbound(IntegrationCredentials integrationCredentials, String rawBody) {
        Map<String, Object> update = objectMapper.readValue(rawBody, Map.class);

        String triggerName;
        Map<String, Object> triggerData = new LinkedHashMap<>();

        if (update.containsKey("callback_query")) {
            triggerName = "telegram.callback_query";
            Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
            triggerData.put("callbackQueryId", callbackQuery.get("id"));
            triggerData.put("data", callbackQuery.get("data"));
            if (callbackQuery.containsKey("message")) {
                Map<String, Object> msg = (Map<String, Object>) callbackQuery.get("message");
                triggerData.put("chatId", ((Map<String, Object>) msg.get("chat")).get("id"));
                triggerData.put("messageId", msg.get("message_id"));
            }
            if (callbackQuery.containsKey("from")) {
                triggerData.put("from", callbackQuery.get("from"));
            }
        } else if (update.containsKey("message")) {
            Map<String, Object> message = (Map<String, Object>) update.get("message");
            Map<String, Object> chat = (Map<String, Object>) message.get("chat");

            triggerData.put("chatId", chat.get("id"));
            triggerData.put("messageId", message.get("message_id"));
            if (message.containsKey("from")) {
                triggerData.put("from", message.get("from"));
            }

            String text = (String) message.get("text");

            if (message.containsKey("photo")) {
                triggerName = "telegram.photo_received";
                triggerData.put("photo", message.get("photo"));
                triggerData.put("caption", message.get("caption"));
            } else if (message.containsKey("document")) {
                triggerName = "telegram.document_received";
                triggerData.put("document", message.get("document"));
                triggerData.put("caption", message.get("caption"));
            } else if (text != null && text.startsWith("/")) {
                triggerName = "telegram.command_received";
                triggerData.put("text", text);
                String[] parts = text.split("\\s+", 2);
                triggerData.put("command", parts[0]);
                if (parts.length > 1) {
                    triggerData.put("args", parts[1]);
                }
            } else {
                triggerName = "telegram.message_received";
                triggerData.put("text", text);
            }
        } else if (update.containsKey("channel_post")) {
            log.debug("Unsupported Telegram update type, keys: {}", update.keySet());
            triggerName = "telegram.channel_post";
            triggerData.put("raw", update);
        } else {
            log.debug("Unsupported Telegram update type, keys: {}", update.keySet());
            triggerName = "telegram.unknown";
            triggerData.put("raw", update);
        }

        return Trigger.createBasic(CONNECTOR_CODE, integrationCredentials.getId().toString(), triggerName, triggerData);
    }

    @Tool(name = "telegram.send_message", value = "Send a text message")
    public Map<String, Object> toolSendMessage(
            @P("Target chat ID") String chatId,
            @P("Message text") String text,
            @P("Parse mode (HTML, Markdown, MarkdownV2)") String parseMode,
            @P("ID of message to reply to") String replyToMessageId,
            @P("Inline keyboard markup as JSON") String replyMarkup) {
        List<String> chunks = splitMessage(text == null ? "" : text, MAX_MESSAGE_LENGTH);
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

    /**
     * Splits {@code text} into chunks of at most {@code limit} characters so each fits a single
     * Telegram message. Prefers to break on a newline, then on whitespace, and only hard-cuts when
     * a single run has no such boundary — keeping all content across messages.
     * <p>
     * Note: a split can fall inside an HTML/MarkdownV2 entity that spans the boundary; breaking on
     * line boundaries first makes that rare, but very long pre-formatted blocks are not entity-safe.
     */
    /**
     * Truncates {@code text} to at most {@code limit} characters, appending an ellipsis when cut.
     * Used where the content cannot be split across messages (a single caption or an edited message).
     */
    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit - 1) + "…";
    }

    static List<String> splitMessage(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= limit) {
            chunks.add(text);
            return chunks;
        }
        int pos = 0;
        int len = text.length();
        while (pos < len) {
            if (len - pos <= limit) {
                chunks.add(text.substring(pos));
                break;
            }
            int window = pos + limit;
            int splitAt = text.lastIndexOf('\n', window - 1);
            if (splitAt <= pos) {
                splitAt = text.lastIndexOf(' ', window - 1);
            }
            if (splitAt <= pos) {
                // No newline/space within the window — hard cut at the limit.
                chunks.add(text.substring(pos, window));
                pos = window;
            } else {
                chunks.add(text.substring(pos, splitAt));
                pos = splitAt + 1; // drop the boundary newline/space
            }
        }
        return chunks;
    }

    @Tool(name = "telegram.send_photo", value = "Send a photo")
    public Map<String, Object> toolSendPhoto(
            @P("Target chat ID") String chatId,
            @P("Photo URL or file ID") String photo,
            @P("Photo caption") String caption) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("photo", photo);
        if (caption != null) apiParams.put("caption", truncate(caption, MAX_CAPTION_LENGTH));
        return sendTelegramRequest("sendPhoto", apiParams);
    }

    @Tool(name = "telegram.edit_message", value = "Edit a message")
    public Map<String, Object> toolEditMessage(
            @P("Chat ID") String chatId,
            @P("Message ID to edit") String messageId,
            @P("New message text") String text) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        apiParams.put("text", truncate(text, MAX_MESSAGE_LENGTH));
        return sendTelegramRequest("editMessageText", apiParams);
    }

    @Tool(name = "telegram.delete_message", value = "Delete a message")
    public Map<String, Object> toolDeleteMessage(
            @P("Chat ID") String chatId,
            @P("Message ID to delete") String messageId) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("message_id", messageId);
        return sendTelegramRequest("deleteMessage", apiParams);
    }

    @Tool(name = "telegram.answer_callback_query", value = "Answer a callback query")
    public Map<String, Object> toolAnswerCallbackQuery(
            @P("Callback query ID") String callbackQueryId,
            @P("Response text") String text) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("callback_query_id", callbackQueryId);
        if (text != null) apiParams.put("text", text);
        return sendTelegramRequest("answerCallbackQuery", apiParams);
    }

    private Map<String, Object> sendTelegramRequest(String method, Map<String, Object> params) {
        String token = credentials().get("token");
        return telegramApiClient.sendRequest(method, token, params);
    }

    @Override
    public Map<String, Object> getPredefinedTriggers() {
        Map<String, Object> triggers = new LinkedHashMap<>();
        triggers.put("telegram.message_received", Map.of(
                "description", "Text message received",
                "params", List.of("chatId", "text", "from", "messageId")
        ));
        triggers.put("telegram.photo_received", Map.of(
                "description", "Photo received",
                "params", List.of("chatId", "photo", "caption", "from", "messageId")
        ));
        triggers.put("telegram.document_received", Map.of(
                "description", "Document received",
                "params", List.of("chatId", "document", "caption", "from", "messageId")
        ));
        triggers.put("telegram.command_received", Map.of(
                "description", "Bot command received",
                "params", List.of("chatId", "text", "command", "args", "from", "messageId")
        ));
        triggers.put("telegram.callback_query", Map.of(
                "description", "Inline button pressed",
                "params", List.of("callbackQueryId", "data", "chatId", "messageId", "from")
        ));
        return triggers;
    }

    @Override
    public List<TaskDescriptor> getBackgroundTasks(IntegrationCredentials credentials) {
        if (!isPollingMode()) {
            return List.of();
        }
        // interval=ZERO — pull-based scheduler сразу поставит next_run_at=now и подхватит
        // строку на следующем тике (≤1с). Один tick = один getUpdates(timeout=20s).
        return List.of(new TaskDescriptor.Periodic(
                TaskScope.global(), // listener подставит integration scope
                TASK_LONG_POLL,
                () -> longPollTick(credentials),
                Duration.ZERO));
    }

    /**
     * Одна итерация long‑polling'а: при необходимости снимаем webhook у Telegram, делаем
     * {@code getUpdates(timeout=20s)}, диспатчим полученные обновления через
     * {@link TriggerRouterService}, обновляем offset в памяти.
     *
     * <p>На любой ошибке scheduler ставит {@code next_run_at = now + 60s} (общий error retry).
     * На успехе — {@code now + 0s}, и следующий tick подхватит строку сразу же.
     */
    @SuppressWarnings("unchecked")
    private void longPollTick(IntegrationCredentials credentials) throws Exception {
        UUID id = credentials.getId();
        String token = decryptCredentials(credentials).get("token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Integration " + id + " has no telegram token");
        }

        if (webhookDeleted.add(id)) {
            try {
                telegramApiClient.deleteWebhook(token);
            } catch (Exception e) {
                // Не логируем e.getMessage() / не передаём cause: Spring RestClient может вложить
                // URL вида /bot{token}/... в текст или стек, что утечёт токен в логи.
                log.warn("Failed to deleteWebhook before polling for {}: {}",
                        id, e.getClass().getSimpleName());
                webhookDeleted.remove(id); // дать шанс ретраю на следующем tick'е
            }
        }

        Long offset = offsets.get(id);
        Map<String, Object> response;
        try {
            response = telegramApiClient.getUpdates(token, offset, LONG_POLL_TIMEOUT_SEC);
        } catch (HttpClientErrorException.Conflict e) {
            // Другой процесс держит getUpdates с этим токеном — пусть scheduler подождёт 60s.
            // Cause не пробрасываем — см. комментарий выше про утечку URL c токеном.
            throw new IllegalStateException(
                    "Telegram 409 Conflict — another process holds long-poll for this bot");
        } catch (Exception e) {
            // Любая другая transport-ошибка телеги: только класс исключения, без message/cause.
            throw new IllegalStateException(
                    "Telegram getUpdates failed: " + e.getClass().getSimpleName());
        }
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("Telegram getUpdates failed: " + response.get("description"));
        }

        List<Map<String, Object>> updates = (List<Map<String, Object>>) response.get("result");
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (Map<String, Object> update : updates) {
            Number updateId = (Number) update.get("update_id");
            if (updateId != null) {
                offsets.put(id, updateId.longValue() + 1);
            }
            dispatch(credentials, update);
        }
    }

    private void dispatch(IntegrationCredentials credentials, Map<String, Object> update) {
        try {
            String rawBody = objectMapper.writeValueAsString(update);
            Trigger trigger = normalizeInbound(credentials, rawBody);
            triggerRouterService.routeWhTrigger(credentials, trigger);
        } catch (Exception e) {
            log.error("Failed to dispatch update for integration {}: {}",
                    credentials.getId(), e.getMessage());
        }
    }

    @Override
    public boolean validateWebhookRequest(IntegrationCredentials integrationCredentials, HttpServletRequest request) {
        String secretToken = request.getHeader(HEADER_SECRET_TOKEN);
        String webhookSecret = integrationCredentials.getWebhookSecret();
        if (secretToken == null || webhookSecret == null) return false;
        return MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                secretToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
