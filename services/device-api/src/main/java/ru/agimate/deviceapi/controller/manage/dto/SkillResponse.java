package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Skill;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Skill response")
public record SkillResponse(
        @Schema(description = "Skill ID")
        UUID id,

        @Schema(description = "Skill name")
        String name,

        @Schema(description = "Skill description")
        String description,

        @Schema(description = "Skill version")
        int version,

        @Schema(description = "Whether the skill is public")
        boolean isPublic,

        @Schema(description = "Whether the skill is featured")
        boolean isFeatured,

        @Schema(description = "Owner user ID")
        UUID userId,

        @Schema(description = "Parent skill ID (if cloned)")
        UUID parentId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was last updated")
        LocalDateTime updatedAt,

        @Schema(description = "ID of the current user's copy of this skill (in public/featured lists)")
        UUID myCopyId
) {
    public static SkillResponse from(Skill skill) {
        return from(skill, null);
    }

    public static SkillResponse from(Skill skill, UUID myCopyId) {
        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getVersion(),
                skill.getIsPublic(),
                skill.getIsFeatured(),
                skill.getUserId(),
                skill.getParentId(),
                skill.getCreatedAt(),
                skill.getUpdatedAt(),
                myCopyId
        );
    }
}
