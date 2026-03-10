package ru.agimate.deviceapi.connectors.integrations;

import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;

import java.util.List;
import java.util.Map;

public interface IntegrationHandler {

    // === Required ===

    String getPlatformCode();

    PlatformValidationResult validateCredentials(Map<String, String> credentials);

    Map<String, Object> executeTool(IntegrationCredentials integrationCredentials, Map<String, String> credentials,
                                    String toolName, Map<String, Object> params);

    Map<String, Object> getPredefinedTools();

    // === Optional: platform metadata (defaults) ===

    default boolean supportsWebhooks() {
        return false;
    }

    default List<String> getCredentialFields() {
        return List.of();
    }

    default String getPlatformName() {
        return getPlatformCode();
    }

    // === Optional: webhooks (default no-op) ===

    default Map<String, Object> getPredefinedTriggers() {
        return Map.of();
    }

    default void setupWebhook(IntegrationCredentials integrationCredentials, Map<String, String> credentials, String webhookUrl) {
        // no-op for platforms without webhooks
    }

    default void removeWebhook(Map<String, String> credentials) {
        // no-op
    }

    default TriggerRequest normalizeInbound(IntegrationCredentials integrationCredentials, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(IntegrationCredentials integrationCredentials, HttpServletRequest request) {
        return false;
    }
}
