package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.abac.AccessEffect;

import java.util.Map;

/**
 * Обновление правила. {@code effect}/{@code description} — частично (null = не менять);
 * {@code paramsFilter} заменяется целиком ({@code null} = очистить фильтр).
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
