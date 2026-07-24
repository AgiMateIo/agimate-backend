package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.service.SystemSkillBootstrap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Skill detail response with SKILL.md body")
public record SkillDetailResponse(
        @Schema(description = "Skill ID")
        UUID id,

        @Schema(description = "Skill name — stable code (referenced by presets; unique per owner)")
        String name,

        @Schema(description = "Human-readable display title (falls back to name if unset)")
        String title,

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

        @Schema(description = "True for a system-owned platform skill (rename/hard-delete restricted; editable by ADMIN)")
        boolean system,

        @Schema(description = "SKILL.md body (without frontmatter)")
        String mdContent,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the skill was last updated")
        LocalDateTime updatedAt
) {
    public static SkillDetailResponse from(Skill skill) {
        return new SkillDetailResponse(
                skill.getId(),
                skill.getName(),
                skill.getTitle() != null ? skill.getTitle() : skill.getName(),
                skill.getDescription(),
                skill.getConnectorCodes(),
                skill.getVersion(),
                skill.getIsPublic(),
                skill.getUserId(),
                SystemSkillBootstrap.SYSTEM_USER_ID.equals(skill.getUserId()),
                skill.getMdContent(),
                skill.getCreatedAt(),
                skill.getUpdatedAt()
        );
    }
}
