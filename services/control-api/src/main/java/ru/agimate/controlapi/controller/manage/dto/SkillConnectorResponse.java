package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.SkillConnector;
import ru.agimate.controlapi.database.enums.SkillConnectorType;

import java.util.UUID;

@Schema(description = "Skill-connector binding response")
public record SkillConnectorResponse(
        @Schema(description = "Binding ID")
        UUID id,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Binding type")
        SkillConnectorType type,

        @Schema(description = "Tool or trigger name")
        String name
) {
    public static SkillConnectorResponse from(SkillConnector entity) {
        return new SkillConnectorResponse(
                entity.getId(),
                entity.getConnectorCode(),
                entity.getType(),
                entity.getName()
        );
    }
}
