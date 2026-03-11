package ru.agimate.deviceapi.connectors.integrations.telegram;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;
import ru.agimate.deviceapi.connectors.integrations.IntegrationValidationResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramHandler implements IntegrationHandler {

    public static final String CONNECTOR_CODE = "telegram";
    private static final String HEADER_SECRET_TOKEN = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramApiClient telegramApiClient;
    private final ObjectMapper objectMapper;

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
    public TriggerRequest normalizeInbound(IntegrationCredentials integrationCredentials, String rawBody) {
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
                // Parse command and args
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

        JsonNode dataNode = objectMapper.valueToTree(triggerData);

        return new TriggerRequest(
                UUID.randomUUID().toString(),
                "integration",
                triggerName,
                CONNECTOR_CODE,
                integrationCredentials.getPubId().toString(),
                Instant.now(),
                dataNode
        );
    }

    @Override
    public Map<String, Object> executeTool(IntegrationCredentials integrationCredentials, Map<String, String> credentials,
                                           String toolName, Map<String, Object> params) {
        String token = credentials.get("token");
        return switch (toolName) {
            case "telegram.send_message" -> {
                Map<String, Object> apiParams = new LinkedHashMap<>();
                apiParams.put("chat_id", params.get("chatId"));
                apiParams.put("text", params.get("text"));
                if (params.containsKey("parseMode")) apiParams.put("parse_mode", params.get("parseMode"));
                if (params.containsKey("replyToMessageId")) apiParams.put("reply_to_message_id", params.get("replyToMessageId"));
                if (params.containsKey("replyMarkup")) apiParams.put("reply_markup", params.get("replyMarkup"));
                yield telegramApiClient.sendMessage(token, apiParams);
            }
            case "telegram.send_photo" -> {
                Map<String, Object> apiParams = new LinkedHashMap<>();
                apiParams.put("chat_id", params.get("chatId"));
                apiParams.put("photo", params.get("photo"));
                if (params.containsKey("caption")) apiParams.put("caption", params.get("caption"));
                yield telegramApiClient.sendPhoto(token, apiParams);
            }
            case "telegram.edit_message" -> {
                Map<String, Object> apiParams = new LinkedHashMap<>();
                apiParams.put("chat_id", params.get("chatId"));
                apiParams.put("message_id", params.get("messageId"));
                apiParams.put("text", params.get("text"));
                yield telegramApiClient.editMessageText(token, apiParams);
            }
            case "telegram.delete_message" -> {
                Map<String, Object> apiParams = new LinkedHashMap<>();
                apiParams.put("chat_id", params.get("chatId"));
                apiParams.put("message_id", params.get("messageId"));
                yield telegramApiClient.deleteMessage(token, apiParams);
            }
            case "telegram.answer_callback_query" -> {
                Map<String, Object> apiParams = new LinkedHashMap<>();
                apiParams.put("callback_query_id", params.get("callbackQueryId"));
                if (params.containsKey("text")) apiParams.put("text", params.get("text"));
                yield telegramApiClient.answerCallbackQuery(token, apiParams);
            }
            default -> throw new BadRequestStatusException("Unknown tool: " + toolName);
        };
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
    public Map<String, Object> getPredefinedTools() {
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("telegram.send_message", Map.of(
                "description", "Send a text message",
                "params", List.of("chatId", "text", "parseMode", "replyToMessageId", "replyMarkup")
        ));
        tools.put("telegram.send_photo", Map.of(
                "description", "Send a photo",
                "params", List.of("chatId", "photo", "caption")
        ));
        tools.put("telegram.edit_message", Map.of(
                "description", "Edit a message",
                "params", List.of("chatId", "messageId", "text")
        ));
        tools.put("telegram.delete_message", Map.of(
                "description", "Delete a message",
                "params", List.of("chatId", "messageId")
        ));
        tools.put("telegram.answer_callback_query", Map.of(
                "description", "Answer a callback query",
                "params", List.of("callbackQueryId", "text")
        ));
        return tools;
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
