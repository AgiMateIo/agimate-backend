package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.abac.AccessEffect;

import java.util.Map;

/**
 * Updating a rule. {@code effect}/{@code description} are partial (null = leave unchanged);
 * {@code paramsFilter} is replaced wholesale ({@code null} = clear the filter).
 */
@Schema(description = "Update an access refinement rule")
public record UpdateAgentConnectionPolicyRequest(
        @Schema(nullable = true, description = "ALLOW or DENY; null = keep")
        AccessEffect effect,

        @Schema(nullable = true, description = "Replaces params filter; null clears it")
        Map<String, Object> paramsFilter,

        @Schema(nullable = true, description = "null = keep")
        String description
) {
}
