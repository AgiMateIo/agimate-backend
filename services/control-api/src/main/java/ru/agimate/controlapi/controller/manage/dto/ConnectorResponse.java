package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

@Schema(description = "Connector response")
public record ConnectorResponse(
        @Schema(description = "Connector code (unique identifier)")
        String code,

        @Schema(description = "Connector display name")
        String name,

        @Schema(description = "Connector description")
        String description,

        @Schema(description = "Capabilities (transportDirection, executionLocus, toolBinding, supportedScopes, defaultScope)")
        ConnectorCapabilities capabilities,

        @Schema(description = "Integration-specific metadata; populated only for integration connectors with a handler", nullable = true)
        IntegrationMeta integrationMeta
) {
    public static ConnectorResponse from(Connector connector) {
        return from(connector, null);
    }

    public static ConnectorResponse from(Connector connector, IntegrationMeta integrationMeta) {
        return new ConnectorResponse(
                connector.getCode(),
                connector.getName(),
                connector.getDescription(),
                connector.capabilities(),
                integrationMeta
        );
    }
}
