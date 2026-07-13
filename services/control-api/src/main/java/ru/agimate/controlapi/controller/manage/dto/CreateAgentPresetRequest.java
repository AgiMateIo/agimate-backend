package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

@Schema(description = "Request to create an agent role preset (ADMIN)")
public record CreateAgentPresetRequest(
        @NotBlank
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*",
                message = "code must be a lowercase kebab-case slug")
        @Schema(description = "Stable preset slug, e.g. 'personal-assistant' (immutable after creation)")
        String code,

        @NotBlank
        @Schema(description = "Preset display name")
        String name,

        @Schema(description = "Preset description for the gallery card")
        String description,

        @NotBlank
        @Schema(description = "Prefill for agent instructions (copied into the agent on creation)")
        String instructions,

        @Schema(description = "System skill names the preset suggests to bind (must exist as system skills)")
        List<String> skillNames,

        @Schema(description = "Gallery sort order (ascending)", defaultValue = "0")
        Integer sortOrder
) {
    public List<String> resolveSkillNames() {
        return skillNames == null ? List.of() : skillNames;
    }

    public int resolveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
