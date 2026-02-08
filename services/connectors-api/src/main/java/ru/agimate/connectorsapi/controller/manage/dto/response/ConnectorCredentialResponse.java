package ru.agimate.connectorsapi.controller.manage.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.ConnectorCredential;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Connector credential information")
public record ConnectorCredentialResponse(
        @Schema(description = "Public ID of the credential")
        UUID id,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Credential name/label")
        String name,

        @Schema(description = "Credential description")
        String description,

        @Schema(description = "Whether the credential is enabled")
        boolean enabled,

        @Schema(description = "Last time the credential was used")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastUsedAt,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {
    public static ConnectorCredentialResponse from(ConnectorCredential credential) {
        return new ConnectorCredentialResponse(
                credential.getPubId(),
                credential.getConnector().getCode(),
                credential.getName(),
                credential.getDescription(),
                credential.getEnabled(),
                credential.getLastUsedAt(),
                credential.getCreatedAt()
        );
    }
}
