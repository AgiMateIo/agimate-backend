package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;

@Schema(description = "A connector preset: the card, the form prefill and the connection identity")
public record ConnectorPresetResponse(
        @Schema(description = "Preset code — stable slug and the ?preset= deep link")
        String code,

        @Schema(description = "Connector the preset belongs to; in v1 always mcp")
        String connectorCode,

        @Schema(description = "What to show in the catalogue")
        ConnectorPresetCard card,

        @Schema(description = "Values the frontend prefills into the connection form")
        ConnectorPresetForm form,

        @Schema(description = "Identity of the connection this preset would create")
        ConnectorPresetConnection connection
) {
}
