package ru.agimate.controlapi.controller.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.ContentCategory;

import java.util.List;

@Schema(description = "Agent preset in the public catalogue — what it does, without its instructions")
public record CatalogPresetResponse(
        @Schema(description = "Preset code — stable, the page's address and the ?preset= of the sign-up link")
        String name,

        @Schema(description = "Display title")
        String title,

        @Schema(description = "What the agent does")
        String description,

        @Schema(description = "Agent type the preset proposes")
        AgentType agentType,

        @Schema(description = "ContentCategory name; labels: GET /public/catalog/taxonomy/")
        ContentCategory category,

        @Schema(description = "ContentTag names")
        List<String> tags,

        @Schema(description = "Public skills the preset bundles")
        List<CatalogSkillRef> skills,

        @Schema(description = "Connectors those skills work with")
        List<CatalogConnectorRef> connectors
) {
    @Schema(description = "Skill a preset bundles")
    public record CatalogSkillRef(
            @Schema(description = "Skill code — the address of its page")
            String name,

            @Schema(description = "Display title")
            String title,

            @Schema(description = "What the skill does")
            String description
    ) {
    }
}
