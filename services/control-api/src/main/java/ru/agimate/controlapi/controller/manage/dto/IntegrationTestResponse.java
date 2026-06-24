package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;

@Schema(description = "Result of testing an integration: credential validation + (MCP) tool reload")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrationTestResponse(
        @Schema(description = "Whether credentials are valid / server reachable")
        boolean valid,

        @Schema(description = "Resolved platform identifier (e.g. bot username, MCP server URL)")
        String identifier,

        @Schema(description = "Human-readable display name")
        String displayName,

        @Schema(description = "Field that failed validation; null if valid")
        String errorField,

        @Schema(description = "Validation error message; null if valid")
        String errorMessage,

        @Schema(description = "Number of tools (re)loaded into cache; null for non-MCP connectors")
        Integer toolsDiscovered,

        @Schema(description = "Tool discovery error (credentials valid, but tools/list failed); null if ok")
        String toolsError
) {
    public static IntegrationTestResponse from(IntegrationValidationResult validation,
                                               Integer toolsDiscovered, String toolsError) {
        return new IntegrationTestResponse(
                validation.valid(),
                validation.identifier(),
                validation.displayName(),
                validation.errorField(),
                validation.errorMessage(),
                toolsDiscovered,
                toolsError);
    }
}
