package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import ru.agimate.controlapi.database.enums.IdentityScope;

import java.util.UUID;

@Schema(description = "Bind a connector to an agent")
public record BindConnectionRequest(
        @NotBlank
        @Schema(description = "Connector code to bind", requiredMode = Schema.RequiredMode.REQUIRED)
        String connectorCode,

        @Schema(description = "Chosen identity scope (∈ connector.supportedScopes). " +
                "Omit to use the connector default. Ignored for INSTANCE (connection is explicit).",
                nullable = true)
        IdentityScope scope,

        @Schema(description = "Connection id — REQUIRED for INSTANCE connectors (which explicit instance). " +
                "Omit for context connectors (AGENT/TEAM/USER), which are materialized by scope.",
                nullable = true)
        UUID connectionId
) {
}
