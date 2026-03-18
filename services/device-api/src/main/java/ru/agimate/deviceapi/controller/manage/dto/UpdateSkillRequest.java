package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.deviceapi.database.entities.SkillType;

@Schema(description = "Request to update a skill")
public record UpdateSkillRequest(
        @NotBlank
        @Schema(description = "Content of SKILL.md with frontmatter")
        String skillMd,

        @NotNull
        @Schema(description = "Skill type")
        SkillType type,

        @Schema(description = "Whether the skill is public")
        Boolean isPublic
) {
    public boolean resolveIsPublic() {
        return isPublic != null && isPublic;
    }
}
