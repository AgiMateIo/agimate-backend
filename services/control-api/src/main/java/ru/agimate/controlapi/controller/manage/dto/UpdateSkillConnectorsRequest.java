package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Request to replace the skill's required connector codes")
public record UpdateSkillConnectorsRequest(
        @NotNull
        @Schema(description = "Connector codes required by the skill (validated against the registry; "
                + "empty list = skill without connectors)")
        List<String> connectorCodes
) {
}
