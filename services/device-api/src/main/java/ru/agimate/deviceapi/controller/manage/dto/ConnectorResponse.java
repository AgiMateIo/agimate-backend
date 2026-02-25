package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.service.ConnectorService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Connector information (without the actual key)")
public record ConnectorResponse(
        @Schema(description = "Public ID of the connector")
        UUID pubId,

        @Schema(description = "Connector name/label", example = "My Home Device")
        String name,

        @Schema(description = "Connector description")
        String description,

        @Schema(description = "Masked key ID for identification", example = "dvckZ3h5****")
        String maskedKeyId,

        @Schema(description = "Whether the connector is enabled")
        boolean enabled,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt,

        @Schema(description = "Device features")
        Map<String, Object> features
) {
    public static ConnectorResponse from(Connector connector) {
        String maskedKeyId = ConnectorService.CONNECTOR_KEY_PREFIX + connector.getKeyId().substring(0, 4) + "****";
        return new ConnectorResponse(
                connector.getPubId(),
                connector.getName(),
                connector.getDescription(),
                maskedKeyId,
                connector.getEnabled(),
                connector.getCreatedAt(),
                connector.getUpdatedAt(),
                connector.getDeviceFeatures()
        );
    }
}
