package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.controller.manage.dto.SkillConnectorResponse;

import java.util.List;
import java.util.UUID;

@Schema(description = "Skill assigned to the agent with attached connectors")
public record AgentSkillWithConnectorsResponse(
        @Schema(description = "Skill public ID")
        UUID skillPubId,

        @Schema(description = "Skill name")
        String skillName,

        @Schema(description = "Connectors attached to this skill")
        List<SkillConnectorResponse> connectors
) {
}
