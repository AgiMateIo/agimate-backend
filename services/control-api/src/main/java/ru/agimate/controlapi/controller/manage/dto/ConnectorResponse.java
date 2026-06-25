package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ConnectorType;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

@Schema(description = "Connector response")
public record ConnectorResponse(
        @Schema(description = "Connector code (unique identifier)")
        String code,

        @Schema(description = "Connector type")
        ConnectorType type,

        @Schema(description = "Connector display name")
        String name,

        @Schema(description = "Connector description")
        String description,

        @Schema(description = "Type-level capabilities (transportDirection, executionLocus, toolBinding, sharingScope)", nullable = true)
        ConnectorCapabilities capabilities,

        @Schema(description = "Integration-specific metadata; populated only when type=INTEGRATION and a handler is registered", nullable = true)
        IntegrationMeta integrationMeta
) {
    public static ConnectorResponse from(Connector connector) {
        return new ConnectorResponse(
                connector.getCode(),
                connector.getType(),
                connector.getName(),
                connector.getDescription(),
                connector.getCapabilities(),
                null
        );
    }

    public static ConnectorResponse from(Connector connector, IntegrationMeta integrationMeta) {
        return new ConnectorResponse(
                connector.getCode(),
                connector.getType(),
                connector.getName(),
                connector.getDescription(),
                connector.getCapabilities(),
                integrationMeta
        );
    }
}
