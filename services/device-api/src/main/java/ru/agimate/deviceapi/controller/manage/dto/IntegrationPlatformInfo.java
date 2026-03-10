package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;

import java.util.List;

@Schema(description = "Integration platform info")
public record IntegrationPlatformInfo(
        @Schema(description = "Platform code")
        String code,

        @Schema(description = "Platform name")
        String name,

        @Schema(description = "Required credential fields")
        List<String> credentialFields,

        @Schema(description = "Whether platform supports webhooks")
        boolean supportsWebhooks
) {
    public static IntegrationPlatformInfo from(IntegrationHandler handler) {
        return new IntegrationPlatformInfo(
                handler.getPlatformCode(),
                handler.getPlatformName(),
                handler.getCredentialFields(),
                handler.supportsWebhooks()
        );
    }
}
