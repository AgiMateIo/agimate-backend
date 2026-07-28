package ru.agimate.controlapi.connectors.integrations.telegram;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.trigger.Trigger;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade of the Telegram connector: credentials, webhooks, triggers. The tools and jobs live in
 * {@link TelegramToolService}, and the dispatch in {@link BaseConnectorHandler}.
 */
@Slf4j
@Component
public class TelegramConnectorService extends BaseConnectorHandler
        implements IntegrationConnectorHandler, TriggerProvider {

    public static final String CONNECTOR_CODE = TelegramUtils.CONNECTOR_CODE;
    public static final String MODE_WEBHOOK = "webhook";
    public static final String MODE_POLLING = "polling";
    private static final String HEADER_SECRET_TOKEN = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramApiClient telegramApiClient;
    private final ObjectMapper objectMapper;
    private final TelegramMediaService mediaService;
    private final ConnectorEnvFactory envFactory;
    private final ConnectionRepository connectionRepository;
    private final String mode;

    public TelegramConnectorService(TelegramToolService toolService,
                                    TelegramApiClient telegramApiClient,
                                    ObjectMapper objectMapper,
                                    TelegramMediaService mediaService,
                                    ConnectorEnvFactory envFactory,
                                    ConnectionRepository connectionRepository,
                                    @Value("${app.integration.telegram.mode:webhook}") String mode) {
        super(toolService);
        this.telegramApiClient = telegramApiClient;
        this.objectMapper = objectMapper;
        this.mediaService = mediaService;
        this.envFactory = envFactory;
        this.connectionRepository = connectionRepository;
        this.mode = mode;
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Telegram";
    }

    @Override
    public String connectorDescription() {
        return "Бот в Telegram: агент отвечает на сообщения в личных чатах и группах, "
                + "отправляет фото, документы и видео.";
    }

    public boolean isPollingMode() {
        return MODE_POLLING.equalsIgnoreCase(mode);
    }

    @Override
    public boolean supportsWebhooks() {
        return !isPollingMode();
    }

    @Override
    public Map<String, String> getCredentialFields() {
        return Map.of("token", "Bot API token");
    }

    /** Long polling is only needed in polling mode; in webhook mode there are no background jobs. */
    @Override
    public Map<String, JobSpec> getJobs() {
        return isPollingMode() ? super.getJobs() : Map.of();
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
    public void setupWebhook(ConnectorEnv env, String webhookUrl) {
        String token = env.credentials().get("token");
        try {
            Map<String, Object> response = telegramApiClient.setWebhook(
                    token, webhookUrl, env.webhookSecret());
            if (!Boolean.TRUE.equals(response.get("ok"))) {
                throw new ConnectorException("Failed to set Telegram webhook: " + response.get("description"));
            }
            log.info("Telegram webhook set to {}", webhookUrl);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to set Telegram webhook: {}", e.getMessage());
            throw new ConnectorException("Failed to set Telegram webhook", e);
        }
    }

    @Override
    public void removeWebhook(ConnectorEnv env) {
        String token = env.credentials().get("token");
        try {
            telegramApiClient.deleteWebhook(token);
            log.info("Telegram webhook removed");
        } catch (Exception e) {
            log.warn("Failed to remove Telegram webhook: {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Trigger normalizeInbound(ConnectorEnv env, String rawBody) {
        Map<String, Object> update = objectMapper.readValue(rawBody, Map.class);
        Trigger trigger = TelegramUtils.normalizeUpdate(update, env.connectionId());
        if (!mediaService.hasMedia(trigger)) {
            return trigger;
        }
        // The webhook hot path does not decrypt the credentials — we fetch the download token lazily, only when
        // the incoming message carries media.
        String token = webhookToken(env);
        return mediaService.materialize(token, env.userId(), env.connectionId(), trigger);
    }

    /** The bot's token for downloading media on the webhook path; {@code null} → materialisation degrades. */
    private String webhookToken(ConnectorEnv env) {
        try {
            Connection connection = connectionRepository.findByIdNotDeleted(UUID.fromString(env.connectionId()))
                    .orElse(null);
            if (connection == null) {
                return null;
            }
            return envFactory.forConnection(connection, null, null, null).credentials().get("token");
        } catch (Exception e) {
            log.warn("Failed to resolve Telegram token for media on connection {}: {}",
                    env.connectionId(), e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean validateWebhookRequest(ConnectorEnv env, HttpServletRequest request) {
        String secretToken = request.getHeader(HEADER_SECRET_TOKEN);
        String webhookSecret = env.webhookSecret();
        if (secretToken == null || webhookSecret == null) return false;
        return MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                secretToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        Map<String, TriggerSpec> triggers = new LinkedHashMap<>();
        triggers.put("message_received", new TriggerSpec(
                "Text message received",
                List.of("chatId", "text", "from", "messageId")));
        triggers.put("photo_received", new TriggerSpec(
                "Photo received",
                List.of("chatId", "photo", "caption", "from", "messageId")));
        triggers.put("document_received", new TriggerSpec(
                "Document received",
                List.of("chatId", "document", "caption", "from", "messageId")));
        triggers.put("command_received", new TriggerSpec(
                "Bot command received",
                List.of("chatId", "text", "command", "args", "from", "messageId")));
        triggers.put("callback_query", new TriggerSpec(
                "Inline button pressed",
                List.of("callbackQueryId", "data", "chatId", "messageId", "from")));
        return triggers;
    }
}
