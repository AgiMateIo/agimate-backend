package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Platform;

import java.util.List;
import java.util.UUID;

@Schema(description = "Platform catalog entry")
public record PlatformResponse(
        @Schema(description = "Platform public ID")
        UUID id,

        @Schema(description = "Platform code")
        String code,

        @Schema(description = "Platform name")
        String name,

        @Schema(description = "Platform description")
        String description,

        @Schema(description = "Platform icon URL")
        String iconUrl,

        @Schema(description = "Platform category")
        String category,

        @Schema(description = "Required credential fields")
        List<String> credentialFields,

        @Schema(description = "Whether platform supports webhooks")
        Boolean supportsWebhooks
) {
    public static PlatformResponse from(Platform platform) {
        return new PlatformResponse(
                platform.getPubId(),
                platform.getCode(),
                platform.getName(),
                platform.getDescription(),
                platform.getIconUrl(),
                platform.getCategory(),
                platform.getCredentialFields(),
                platform.getSupportsWebhooks()
        );
    }
}
