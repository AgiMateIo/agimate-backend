package ru.agimate.connectorsapi.controller.api.connectors.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body schema information")
public record RequestBodySchema(
        @Schema(description = "List of body fields")
        List<ParameterInfo> fields,

        @Schema(description = "Example request body as JSON string")
        String example
) {
}
