package ru.agimate.controlapi.controller.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;

@Schema(description = "A connector preset in the public catalogue — the card and availability, never the form")
public record CatalogConnectorPresetResponse(
        @Schema(description = "Preset code — stable slug and the ?preset= of the sign-up link")
        String code,

        @Schema(description = "Connector the preset belongs to; in v1 always mcp")
        String connectorCode,

        @Schema(description = "What to show in the catalogue")
        CatalogConnectorPresetCard card,

        @Schema(description = "Whether the card can be opened")
        CatalogConnectorPresetConnection connection
) {
    @Schema(description = "The card part of a public connector preset")
    public record CatalogConnectorPresetCard(
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

    @Schema(description = "Public availability of the card")
    public record CatalogConnectorPresetConnection(
            @Schema(description = "A hint, not a creation gate; AVAILABLE means the form can be opened after sign-in")
            ConnectorPresetAvailability availability
    ) {
    }
}
