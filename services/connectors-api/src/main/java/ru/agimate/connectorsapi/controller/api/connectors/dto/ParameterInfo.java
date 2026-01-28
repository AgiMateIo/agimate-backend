package ru.agimate.connectorsapi.controller.api.connectors.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Information about method parameter")
public record ParameterInfo(
        @Schema(description = "Parameter name", example = "productId")
        String name,

        @Schema(description = "Parameter type", example = "integer")
        String type,

        @Schema(description = "Whether parameter is required", example = "true")
        boolean required,

        @Schema(description = "Parameter description", example = "Product identifier in Ozon system")
        String description
) {
}
