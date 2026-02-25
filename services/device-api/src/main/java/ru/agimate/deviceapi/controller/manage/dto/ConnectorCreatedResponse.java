package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Connector;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response when a new connector is created (includes the actual key)")
public record ConnectorCreatedResponse(
        @Schema(description = "Public ID of the connector")
        UUID pubId,

        @Schema(description = "Connector name/label")
        String name,

        @Schema(description = "The actual key - SAVE THIS NOW, it will not be shown again!")
        String fullKey,

        @Schema(description = "Connector description")
        String description,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
    public static ConnectorCreatedResponse from(Connector connector, String plaintextKey) {
        return new ConnectorCreatedResponse(
                connector.getPubId(),
                connector.getName(),
                plaintextKey,
                connector.getDescription(),
                connector.getCreatedAt(),
                connector.getUpdatedAt()
        );
    }
}
