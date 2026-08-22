package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to update a skill")
public record UpdateSkillRequest(
        @NotBlank
        @Schema(description = "Content of SKILL.md with frontmatter")
        String skillMd,

        @Schema(description = "Whether the skill is public; omit to keep the current visibility")
        Boolean isPublic
) {
}
