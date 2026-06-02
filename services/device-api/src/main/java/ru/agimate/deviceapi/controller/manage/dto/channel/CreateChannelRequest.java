package ru.agimate.deviceapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Request to create a channel")
public record CreateChannelRequest(
        @NotNull
        @Schema(description = "Agent public ID")
        UUID agentId,

        @NotBlank
        @Schema(description = "Channel display name")
        String name,

        @NotBlank
        @Schema(description = "Connector code that emits the trigger")
        String triggerConnectorCode,

        @NotBlank
        @Schema(description = "Connector identity (App.id or IntegrationCredentials.id)")
        String triggerIdentity,

        @NotBlank
        @Schema(description = "Trigger name to bind to")
        String triggerName,

        @NotBlank
        @Schema(description = "Dot-path into trigger.data with the user-visible message text")
        String triggerMessageField,

        @NotBlank
        @Schema(description = "Connector code used to send the reply")
        String replyConnectorCode,

        @NotBlank
        @Schema(description = "Reply connector identity")
        String replyIdentity,

        @NotBlank
        @Schema(description = "Reply tool name")
        String replyToolName,

        @NotNull
        @Schema(description = "Reply tool params template with {text} and {trigger.*} placeholders")
        Map<String, Object> replyToolParams,

        @Schema(description = "Optional input filter (dot-path → value) applied to trigger.data")
        Map<String, Object> inputFilter
) {}
