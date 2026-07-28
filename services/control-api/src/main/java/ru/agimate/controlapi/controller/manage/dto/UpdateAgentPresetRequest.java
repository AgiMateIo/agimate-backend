package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Partial update of a preset (ADMIN). Every field is optional; {@code null} means «leave unchanged».
 * {@code name} (the machine slug) is deliberately absent: it is the key of idempotent seeding and of
 * the {@code agents.preset_name} analytics, and it is immutable.
 */
@Schema(description = "Partial update of an agent role preset (ADMIN); null fields are left unchanged")
public record UpdateAgentPresetRequest(
        @Schema(description = "Preset display title")
        String title,

        @Schema(description = "Preset description for the gallery card")
        String description,

        @Schema(description = "Prefill for agent instructions")
        String instructions,

        @Schema(description = "System skill names the preset suggests to bind (replaces the list)")
        List<String> skillNames,

        @Schema(description = "Gallery sort order (ascending)")
        Integer sortOrder,

        @Schema(description = "Whether the preset is offered in the gallery")
        Boolean enabled
) {
}
