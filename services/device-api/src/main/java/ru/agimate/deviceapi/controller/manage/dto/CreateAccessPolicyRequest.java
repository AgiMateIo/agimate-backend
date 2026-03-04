package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to create an access policy")
public record CreateAccessPolicyRequest(
        @NotNull
        @Schema(description = "Agent name")
        String agentName,

        @Schema(description = "Connector name (null = wildcard)")
        String connectorName,

        @Schema(description = "Connector identity (null = wildcard)")
        String connectorIdentity,

        @Schema(description = "Tool name (null = wildcard)")
        String toolName,

        @NotNull
        @Schema(description = "Effect: ALLOW or DENY")
        String effect,

        @Schema(description = "Manual priority (overrides auto-specificity)")
        Integer priority,

        @Schema(description = "Human-readable description")
        String description
) {}
