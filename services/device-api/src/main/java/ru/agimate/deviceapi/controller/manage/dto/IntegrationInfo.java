package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;

import java.util.List;

@Schema(description = "Integration platform info")
public record IntegrationInfo(
        @Schema(description = "Connector code")
        String code,

        @Schema(description = "Connector name")
        String name,

        @Schema(description = "Required credential fields")
        List<String> credentialFields,

        @Schema(description = "Whether platform supports webhooks")
        boolean supportsWebhooks
) {
    public static IntegrationInfo from(IntegrationHandler handler) {
        return new IntegrationInfo(
                handler.getConnectorCode(),
                handler.getConnectorName(),
                handler.getCredentialFields(),
                handler.supportsWebhooks()
        );
    }
}
