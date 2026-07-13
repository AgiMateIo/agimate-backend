package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Agent role preset — a wizard prefill: editable instructions plus skills to bind")
public record AgentPresetResponse(
        @Schema(description = "Preset ID")
        UUID id,

        @Schema(description = "Stable preset slug, e.g. 'personal-assistant'")
        String code,

        @Schema(description = "Preset display name")
        String name,

        @Schema(description = "Preset description for the gallery card")
        String description,

        @Schema(description = "Prefill for agent instructions (user edits it freely in the wizard)")
        String instructions,

        @Schema(description = "Skills the preset suggests to bind")
        List<PresetSkill> skills,

        @Schema(description = "Connector codes required by the preset's skills (union, display hint)")
        List<String> connectorCodes
) {
    @Schema(description = "Skill referenced by a preset")
    public record PresetSkill(
            @Schema(description = "Skill ID")
            UUID id,

            @Schema(description = "Skill name")
            String name,

            @Schema(description = "Skill description")
            String description
    ) {
    }
}
