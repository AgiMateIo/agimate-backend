package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Skill;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Skill detail response with SKILL.md content")
public record SkillDetailResponse(
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

        @Schema(description = "Content of SKILL.md")
        String skillMd,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was last updated")
        LocalDateTime updatedAt
) {
    public static SkillDetailResponse from(Skill skill, String skillMd) {
        return new SkillDetailResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getVersion(),
                skill.getIsPublic(),
                skill.getIsFeatured(),
                skill.getUserId(),
                skill.getParentId(),
                skillMd,
                skill.getCreatedAt(),
                skill.getUpdatedAt()
        );
    }
}
