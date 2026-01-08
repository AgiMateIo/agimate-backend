package ru.agimate.connectorsapi.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Information about connector method")
public record MethodInfo(
        @Schema(description = "Method name", example = "getProductList")
        String name,

        @Schema(description = "Display name from OpenAPI summary", example = "Get product list")
        String displayName,

        @Schema(description = "Method description from OpenAPI", example = "Returns paginated list of seller products")
        String description,

        @Schema(description = "HTTP method", example = "POST", allowableValues = {"GET", "POST", "PUT", "DELETE", "PATCH"})
        String httpMethod,

        @Schema(description = "Full endpoint path", example = "/api/call/ozon/getProductList")
        String endpoint,

        @Schema(description = "List of method parameters (path and query parameters)")
        List<ParameterInfo> parameters,

        @Schema(description = "Request body schema (for POST/PUT/PATCH methods)")
        RequestBodySchema requestBodySchema
) {
}
