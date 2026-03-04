package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to create an agent")
public record CreateAgentRequest(
        @NotNull
        @Schema(description = "API key public ID")
        UUID apiKeyPubId,

        @NotNull
        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Allow all triggers")
        boolean triggersAllowAll,

        @NotNull
        @Schema(description = "Triggers destination: centrifugo, webhook, or ignore")
        String triggersTo,

        @Schema(description = "Webhook URL (required when triggersTo is 'webhook')")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Agentic team public ID")
        UUID agenticTeamPubId
) {
}
