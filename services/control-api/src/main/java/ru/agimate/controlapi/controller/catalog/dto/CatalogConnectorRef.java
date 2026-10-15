package ru.agimate.controlapi.controller.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Connector in the public catalogue")
public record CatalogConnectorRef(
        @Schema(description = "Connector code")
        String code,

        @Schema(description = "Display name")
        String name,

        @Schema(description = "What it connects the agent to")
        String description
) {
}
