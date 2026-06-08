package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.integrations.IntegrationHandler;

import java.util.List;

@Schema(description = "Integration-specific metadata for INTEGRATION-type connectors")
public record IntegrationMeta(
        @Schema(description = "Required credential field names")
        List<String> credentialFields,

        @Schema(description = "Whether the integration supports webhooks")
        boolean supportsWebhooks
) {
    public static IntegrationMeta from(IntegrationHandler handler) {
        return new IntegrationMeta(
                handler.getCredentialFields(),
                handler.supportsWebhooks()
        );
    }
}
