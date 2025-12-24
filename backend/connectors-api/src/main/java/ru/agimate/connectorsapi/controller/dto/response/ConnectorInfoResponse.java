package ru.agimate.connectorsapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.Connector;

import java.util.List;
import java.util.UUID;

@Schema(description = "Connector information")
public record ConnectorInfoResponse(
        @Schema(description = "Public ID of the connector")
        UUID id,

        @Schema(description = "Connector code")
        String code,

        @Schema(description = "Connector display name")
        String name,

        @Schema(description = "Connector description")
        String description,

        @Schema(description = "Base URL for API calls")
        String baseUrl,

        @Schema(description = "Icon URL")
        String iconUrl,

        @Schema(description = "Required credential fields")
        List<String> requiredCredentialFields,

        @Schema(description = "Whether method definitions are available")
        boolean hasMethodDefinitions
) {
    public static ConnectorInfoResponse from(Connector connector, List<String> requiredFields, boolean hasMethods) {
        return new ConnectorInfoResponse(
                connector.getPubId(),
                connector.getCode(),
                connector.getName(),
                connector.getDescription(),
                connector.getBaseUrl(),
                connector.getIconUrl(),
                requiredFields,
                hasMethods
        );
    }
}
