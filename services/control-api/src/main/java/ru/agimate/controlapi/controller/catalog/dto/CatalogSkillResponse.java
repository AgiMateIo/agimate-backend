package ru.agimate.controlapi.controller.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ContentCategory;

import java.util.List;

@Schema(description = "Skill in the public catalogue — what it does, without its body")
public record CatalogSkillResponse(
        @Schema(description = "Skill code — stable, the address of its page")
        String name,

        @Schema(description = "Display title")
        String title,

        @Schema(description = "What the skill does")
        String description,

        @Schema(description = "ContentCategory name; labels: GET /public/catalog/taxonomy/")
        ContentCategory category,

        @Schema(description = "ContentTag names")
        List<String> tags,

        @Schema(description = "Connectors the skill works with")
        List<CatalogConnectorRef> connectors,

        @Schema(description = "Presets that bundle the skill")
        List<CatalogPresetRef> presets
) {
    @Schema(description = "Preset that bundles a skill")
    public record CatalogPresetRef(
            @Schema(description = "Preset code — the address of its page")
            String name,

            @Schema(description = "Display title")
            String title
    ) {
    }
}
