package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ContentCategory;

@Schema(description = "The card part of a connector preset")
public record ConnectorPresetCard(
        @Schema(description = "Display name in the requested language")
        String name,

        @Schema(description = "Short description in the requested language")
        String description,

        @Schema(description = "Vendor icon; the frontend shows a monogram when absent")
        String iconUrl,

        @Schema(description = "ContentCategory name; labels: GET /public/catalog/taxonomy/")
        ContentCategory category,

        @Schema(description = "ISO 3166-1 alpha-2 country of the vendor; null when not researched")
        String jurisdiction,

        @Schema(description = "Primary supported auth method; UNKNOWN means none is supported")
        ConnectorPresetAuth auth,

        @Schema(description = "Where to get a token or read the vendor's setup guide")
        String docsUrl
) {
}
