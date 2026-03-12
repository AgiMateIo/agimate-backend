package ru.agimate.deviceapi.connectors.integrations.telegram;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.connectors.integrations.BaseIntegrationHandler;
import ru.agimate.deviceapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.deviceapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.deviceapi.service.trigger.Trigger;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Component
public class TelegramHandler extends BaseIntegrationHandler {

    public static final String CONNECTOR_CODE = "telegram";
    private static final String HEADER_SECRET_TOKEN = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramApiClient telegramApiClient;
    private final ObjectMapper objectMapper;

    public TelegramHandler(IntegrationEncryptionService encryptionService,
                           TelegramApiClient telegramApiClient,
                           ObjectMapper objectMapper) {
        super(encryptionService);
        this.telegramApiClient = telegramApiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getConnectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public boolean supportsWebhooks() {
        return true;
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
        } else {
            log.debug("Unsupported Telegram update type, keys: {}", update.keySet());
            triggerName = "telegram.unknown";
            triggerData.put("raw", update);
        }

        return Trigger.createBasic(CONNECTOR_CODE, integrationCredentials.getPubId().toString(), triggerName, triggerData);
    }

    @Tool(name = "telegram.send_message", value = "Send a text message")
    public Map<String, Object> toolSendMessage(
            @P("Target chat ID") String chatId,
            @P("Message text") String text,
            @P("Parse mode (HTML, Markdown, MarkdownV2)") String parseMode,
            @P("ID of message to reply to") String replyToMessageId,
            @P("Inline keyboard markup as JSON") String replyMarkup) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("text", text);
        if (parseMode != null) apiParams.put("parse_mode", parseMode);
        if (replyToMessageId != null) apiParams.put("reply_to_message_id", replyToMessageId);
        if (replyMarkup != null) apiParams.put("reply_markup", replyMarkup);
        return sendTelegramRequest("sendMessage", apiParams);
    }

    @Tool(name = "telegram.send_photo", value = "Send a photo")
    public Map<String, Object> toolSendPhoto(
            @P("Target chat ID") String chatId,
            @P("Photo URL or file ID") String photo,
            @P("Photo caption") String caption) {
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("chat_id", chatId);
        apiParams.put("photo", photo);
        if (caption != null) apiParams.put("caption", caption);
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
        apiParams.put("text", text);
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
