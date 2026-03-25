package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;

@Schema(description = "Connector response")
public record ConnectorResponse(
        @Schema(description = "Connector code (unique identifier)")
        String code,

        @Schema(description = "Connector type")
        ConnectorType type,

        @Schema(description = "Connector display name")
        String name,

        @Schema(description = "Connector description")
        String description
) {
    public static ConnectorResponse from(Connector connector) {
        return new ConnectorResponse(
                connector.getCode(),
                connector.getType(),
                connector.getName(),
                connector.getDescription()
        );
    }
}
