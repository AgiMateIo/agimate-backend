package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to create a skill")
public record CreateSkillRequest(
        @NotBlank
        @Schema(description = "Content of SKILL.md with frontmatter")
        String skillMd,

        @Schema(description = "Whether the skill is public", defaultValue = "false")
        Boolean isPublic
) {
    public boolean resolveIsPublic() {
        return isPublic != null && isPublic;
    }
}
