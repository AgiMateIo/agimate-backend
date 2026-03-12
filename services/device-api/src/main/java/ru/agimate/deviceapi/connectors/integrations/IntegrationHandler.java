package ru.agimate.deviceapi.connectors.integrations;

import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.servlet.http.HttpServletRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;

public interface IntegrationHandler {

    String getConnectorCode();

    default String getConnectorName() {
        return getConnectorCode();
    }

    default List<String> getCredentialFields() {
        return List.of();
    }

    default Map<String, Object> getPredefinedTriggers() {
        return Map.of();
    }

    Map<String, ToolSpecification> getPredefinedTools();

    IntegrationValidationResult validateCredentials(Map<String, String> credentials);

    Map<String, Object> executeTool(IntegrationCredentials integrationCredentials,
                                    String toolName, Map<String, Object> params);


    default boolean supportsWebhooks() {
        return false;
    }


    default void setupWebhook(IntegrationCredentials integrationCredentials, Map<String, String> credentials, String webhookUrl) {
        // no-op for platforms without webhooks
    }

    default void removeWebhook(Map<String, String> credentials) {
        // no-op
    }

    default Trigger normalizeInbound(IntegrationCredentials integrationCredentials, String rawBody) {
        throw new UnsupportedOperationException("Platform does not support inbound webhooks");
    }

    default boolean validateWebhookRequest(IntegrationCredentials integrationCredentials, HttpServletRequest request) {
        return false;
    }
}
