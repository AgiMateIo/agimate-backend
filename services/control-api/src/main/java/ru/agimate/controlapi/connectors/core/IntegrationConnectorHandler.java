package ru.agimate.controlapi.connectors.core;

import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Map;

/**
 * Коннектор к внешней платформе: живёт от имени пользователя по его credentials
 * ({@code integration_credentials}), опционально принимает входящие webhooks.
 */
public interface IntegrationConnectorHandler extends ConnectorHandler {

    /** Поля credentials: код поля → человекочитаемое название. */
    Map<String, String> getCredentialFields();

    IntegrationValidationResult validateCredentials(Map<String, String> credentials);

    default boolean supportsWebhooks() {
        return false;
    }

    default void setupWebhook(ConnectorContext context, String webhookUrl) {
        // no-op for platforms without webhooks
    }

    default void removeWebhook(ConnectorContext context) {
        // no-op
    }

    /** Нормализация сырого webhook-тела в {@link Trigger}; контекст без расшифровки credentials. */
    default Trigger normalizeInbound(ConnectorContext context, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(ConnectorContext context, HttpServletRequest request) {
        return false;
    }
}
