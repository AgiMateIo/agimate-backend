package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentSkill;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Agent-skill binding response")
public record AgentSkillResponse(
        @Schema(description = "Binding ID")
        UUID id,

        @Schema(description = "Agent public ID")
        UUID agentId,

        @Schema(description = "Skill public ID")
        UUID skillId,

        @Schema(description = "Skill name")
        String skillName,

        @Schema(description = "Whether the skill's connectors have changed since installation")
        boolean needsReinstall,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the binding was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the binding was last updated")
        LocalDateTime updatedAt
) {
    public static AgentSkillResponse from(AgentSkill agentSkill, String skillName, boolean needsReinstall) {
        return new AgentSkillResponse(
                agentSkill.getId(),
                agentSkill.getAgentId(),
                agentSkill.getSkillId(),
                skillName,
                needsReinstall,
                agentSkill.getCreatedAt(),
                agentSkill.getUpdatedAt()
        );
    }
}
