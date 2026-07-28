package ru.agimate.controlapi.connectors.core;

import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Map;

/**
 * A connector to an external platform: it acts on the user's behalf using their credentials (the
 * secret is addressed by {@code connections.secret_id}) and optionally accepts incoming webhooks.
 */
public interface IntegrationConnectorHandler extends ConnectorHandler {

    /** Credentials fields: field code → human-readable name. */
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

    /** Normalisation of a raw webhook body into a {@link Trigger}; the context has no decrypted credentials. */
    default Trigger normalizeInbound(ConnectorEnv env, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(ConnectorEnv env, HttpServletRequest request) {
        return false;
    }
}
