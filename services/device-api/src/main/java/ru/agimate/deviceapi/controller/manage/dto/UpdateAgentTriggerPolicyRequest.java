package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to update an agent trigger policy")
public record UpdateAgentTriggerPolicyRequest(
        @Schema(description = "Connector code (null = wildcard)")
        String connectorCode,

        @Schema(description = "Connector identity (null = wildcard)")
        String connectorIdentity,

        @Schema(description = "Trigger name (null = wildcard)")
        String triggerName,

        @Schema(description = "Effect: ALLOW or DENY")
        String effect,

        @Schema(description = "Manual priority (overrides auto-specificity)")
        Integer priority,

        @Schema(description = "Human-readable description")
        String description
) {}
