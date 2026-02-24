package ru.agimate.deviceapi.integration;

import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.Integration;

import java.util.Map;

public interface IntegrationPlatformHandler {

    // === Required ===

    String getPlatformType();

    PlatformValidationResult validateCredentials(Map<String, String> credentials);

    Map<String, Object> executeTool(Integration integration, Map<String, String> credentials,
                                    String toolName, Map<String, Object> params);

    Map<String, Object> getPredefinedTools();

    // === Optional: webhooks (default no-op) ===

    default Map<String, Object> getPredefinedTriggers() {
        return Map.of();
    }

    default void setupWebhook(Integration integration, Map<String, String> credentials, String webhookUrl) {
        // no-op for platforms without webhooks
    }

    default void removeWebhook(Map<String, String> credentials) {
        // no-op
    }

    default TriggerRequest normalizeInbound(Integration integration, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(Integration integration, HttpServletRequest request) {
        return false;
    }
}
