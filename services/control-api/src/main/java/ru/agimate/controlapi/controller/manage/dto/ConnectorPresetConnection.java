package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;

@Schema(description = "Identity and availability of the connection a preset would create")
public record ConnectorPresetConnection(
        @Schema(description = "What MCP puts into ConnectionResponse.subCode; compare it with an existing connection")
        String identity,

        @Schema(description = "A card hint, not a creation gate; AVAILABLE enables the form")
        ConnectorPresetAvailability availability
) {
}
