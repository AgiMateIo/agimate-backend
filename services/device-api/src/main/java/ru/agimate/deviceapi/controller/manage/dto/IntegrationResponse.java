package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Integration details")
public record IntegrationResponse(
        @Schema(description = "Integration public ID")
        UUID id,

        @Schema(description = "Platform code")
        String platformCode,

        @Schema(description = "Platform name")
        String platformName,

        @Schema(description = "Platform identifier (e.g. bot username)")
        String platformIdentifier,

        @Schema(description = "Integration name")
        String name,

        @Schema(description = "Associated connector code")
        String connectorCode,

        @Schema(description = "Whether integration is enabled")
        Boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last used timestamp")
        LocalDateTime lastUsedAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static IntegrationResponse from(IntegrationCredentials ic, IntegrationHandler handler) {
        return new IntegrationResponse(
                ic.getPubId(),
                ic.extractPlatformCode(),
                handler.getPlatformName(),
                ic.getPlatformIdentifier(),
                ic.getName(),
                ic.getConnectorCode(),
                ic.getEnabled(),
                ic.getLastUsedAt(),
                ic.getCreatedAt()
        );
    }
}
