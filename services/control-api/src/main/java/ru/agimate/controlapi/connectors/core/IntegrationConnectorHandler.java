package ru.agimate.controlapi.connectors.core;

import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Map;

/**
 * Коннектор к внешней платформе: живёт от имени пользователя по его credentials
 * (секрет адресуется {@code connections.secret_id}), опционально принимает входящие webhooks.
 */
public interface IntegrationConnectorHandler extends ConnectorHandler {

    /** Поля credentials: код поля → человекочитаемое название. */
    Map<String, String> getCredentialFields();

    IntegrationValidationResult validateCredentials(Map<String, String> credentials);

    default boolean supportsWebhooks() {
        return false;
    }

    default void setupWebhook(ConnectorEnv env, String webhookUrl) {
        // no-op for platforms without webhooks
    }

    default void removeWebhook(ConnectorEnv env) {
        // no-op
    }

    /** Нормализация сырого webhook-тела в {@link Trigger}; контекст без расшифровки credentials. */
    default Trigger normalizeInbound(ConnectorEnv env, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(ConnectorEnv env, HttpServletRequest request) {
        return false;
    }
}
