package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.enums.PolicyKind;

import java.util.Map;

@Schema(description = "Create an access refinement rule on a binding")
public record CreateAgentConnectionPolicyRequest(
        @NotNull
        @Schema(description = "TOOL or TRIGGER", requiredMode = Schema.RequiredMode.REQUIRED)
        PolicyKind kind,

        @Schema(description = "Tool/trigger name; omit (null) for a binding-wide rule (whole connector)",
                nullable = true)
        String name,

        @NotNull
        @Schema(description = "ALLOW or DENY", requiredMode = Schema.RequiredMode.REQUIRED)
        AccessEffect effect,

        @Schema(description = "Params filter (TOOL — args; TRIGGER — event params)", nullable = true)
        Map<String, Object> paramsFilter,

        @Schema(nullable = true)
        String description
) {
}
