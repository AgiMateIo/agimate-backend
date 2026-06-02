package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Integration credentials details")
public record IntegrationResponse(
        @Schema(description = "Integration credentials public ID")
        UUID id,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Platform identifier (e.g. bot username)")
        String platformIdentifier,

        @Schema(description = "Integration name")
        String name,

        @Schema(description = "Whether integration is enabled")
        Boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last used timestamp")
        LocalDateTime lastUsedAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static IntegrationResponse from(IntegrationCredentials ic) {
        return new IntegrationResponse(
                ic.getId(),
                ic.getConnectorCode(),
                ic.getPlatformIdentifier(),
                ic.getName(),
                ic.getEnabled(),
                ic.getLastUsedAt(),
                ic.getCreatedAt()
        );
    }
}
