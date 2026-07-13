package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Частичное обновление пресета (ADMIN). Все поля опциональны; {@code null} — «не менять».
 * {@code code} отсутствует намеренно: слаг — ключ идемпотентного сидинга и аналитики
 * {@code agents.preset_code}, он неизменяем.
 */
@Schema(description = "Partial update of an agent role preset (ADMIN); null fields are left unchanged")
public record UpdateAgentPresetRequest(
        @Schema(description = "Preset display name")
        String name,

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
