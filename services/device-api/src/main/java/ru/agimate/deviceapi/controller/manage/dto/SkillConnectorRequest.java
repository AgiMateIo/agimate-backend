package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import ru.agimate.deviceapi.database.enums.SkillConnectorType;

@Schema(description = "Request to create a skill-connector binding")
public record SkillConnectorRequest(
        @NotBlank
        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Binding type: TOOL or TRIGGER")
        SkillConnectorType type,

        @Schema(description = "Tool or trigger name")
        String name
) {}
