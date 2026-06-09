package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;

import java.util.Map;

@Schema(description = "Integration-specific metadata for INTEGRATION-type connectors")
public record IntegrationMeta(
        @Schema(description = "Required credential fields: field code to human-readable label")
        Map<String, String> credentialFields,

        @Schema(description = "Whether the integration supports webhooks")
        boolean supportsWebhooks
) {
    public static IntegrationMeta from(IntegrationConnectorHandler handler) {
        return new IntegrationMeta(
                handler.getCredentialFields(),
                handler.supportsWebhooks()
        );
    }
}
