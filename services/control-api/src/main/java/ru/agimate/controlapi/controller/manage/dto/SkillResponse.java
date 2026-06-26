package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Skill;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Skill response")
public record SkillResponse(
        @Schema(description = "Skill ID")
        UUID id,

        @Schema(description = "Skill name")
        String name,

        @Schema(description = "Skill description")
        String description,

        @Schema(description = "Connectors required by the skill")
        List<String> connectorCodes,

        @Schema(description = "Skill version")
        int version,

        @Schema(description = "Whether the skill is public")
        boolean isPublic,

        @Schema(description = "Owner user ID")
        UUID userId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was last updated")
        LocalDateTime updatedAt
) {
    public static SkillResponse from(Skill skill) {
        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getConnectorCodes(),
                skill.getVersion(),
                skill.getIsPublic(),
                skill.getUserId(),
                skill.getCreatedAt(),
                skill.getUpdatedAt()
        );
    }
}
