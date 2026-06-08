package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to create an agent trigger policy")
public record CreateAgentTriggerPolicyRequest(
        @NotNull
        @Schema(description = "Agent public ID")
        UUID agentId,

        @Schema(description = "Connector code (null = wildcard)")
        String connectorCode,

        @Schema(description = "Connector identity (null = wildcard)")
        String connectorIdentity,

        @Schema(description = "Trigger name (null = wildcard)")
        String triggerName,

        @NotNull
        @Schema(description = "Effect: ALLOW or DENY")
        String effect,

        @Schema(description = "Manual priority (overrides auto-specificity)")
        Integer priority,

        @Schema(description = "Human-readable description")
        String description
) {}
