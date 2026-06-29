package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Skill assigned to the agent with required connectors")
public record AgentSkillWithConnectorsResponse(
        @Schema(description = "Skill public ID")
        UUID skillId,

        @Schema(description = "Skill name")
        String skillName,

        @Schema(description = "Skill description")
        String description,

        @Schema(description = "Connector codes (types) required by the skill", example = "[\"board\", \"time\"]")
        List<String> connectorCodes
) {
}
