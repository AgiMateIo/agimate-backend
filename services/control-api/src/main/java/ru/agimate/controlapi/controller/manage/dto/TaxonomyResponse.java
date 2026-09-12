package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The catalogue's vocabulary: the sections skills and presets are filed under, and the tag chips that
 * cut across them. The one place labels come from — a skill or a preset carries only codes, so a
 * caption is not repeated in every row of the catalogue.
 */
@Schema(description = "Vocabulary of the catalogue: categories and tags with localised labels")
public record TaxonomyResponse(
        @Schema(description = "Categories in display order; the one axis of the catalogue")
        List<Item> categories,

        @Schema(description = "Tags grouped for the filter panel; an item's own tags are a flat list")
        List<TagGroup> tagGroups
) {
    @Schema(description = "A vocabulary entry: the code stored on skills and presets, and its label")
    public record Item(
            @Schema(description = "Code as stored and as filtered by, e.g. FINANCE or OWN_TOKEN")
            String code,

            @Schema(description = "Label in the installation's content language")
            String label
    ) {
    }

    @Schema(description = "A group of tags — presentation only, the data has no group")
    public record TagGroup(
            @Schema(description = "Group code, e.g. AUDIENCE")
            String code,

            @Schema(description = "Group label in the installation's content language")
            String label,

            @Schema(description = "Tags of the group in display order")
            List<Item> tags
    ) {
    }
}
