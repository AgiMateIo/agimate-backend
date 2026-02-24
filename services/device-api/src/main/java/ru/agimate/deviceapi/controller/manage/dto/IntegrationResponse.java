package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Integration;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Integration details")
public record IntegrationResponse(
        @Schema(description = "Integration public ID")
        UUID id,

        @Schema(description = "Platform type")
        String platformType,

        @Schema(description = "Platform code")
        String platformCode,

        @Schema(description = "Platform name")
        String platformName,

        @Schema(description = "Platform identifier (e.g. bot username)")
        String platformIdentifier,

        @Schema(description = "Integration name")
        String name,

        @Schema(description = "Associated app public ID")
        UUID appPubId,

        @Schema(description = "Whether integration is enabled")
        Boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last used timestamp")
        LocalDateTime lastUsedAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static IntegrationResponse from(Integration integration) {
        return new IntegrationResponse(
                integration.getPubId(),
                integration.getPlatformType(),
                integration.getPlatform().getCode(),
                integration.getPlatform().getName(),
                integration.getPlatformIdentifier(),
                integration.getName(),
                integration.getApp().getPubId(),
                integration.getEnabled(),
                integration.getLastUsedAt(),
                integration.getCreatedAt()
        );
    }
}
