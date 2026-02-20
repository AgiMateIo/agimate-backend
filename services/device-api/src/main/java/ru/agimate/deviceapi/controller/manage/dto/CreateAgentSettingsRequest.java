package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "Request to create agent settings")
public record CreateAgentSettingsRequest(
        @NotNull
        @Schema(description = "API key public ID")
        UUID apiKeyPubId,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Allow all triggers")
        boolean triggersAllowAll,

        @NotNull
        @Schema(description = "Triggers destination: centrifugo, webhook, or ignore")
        String triggersTo,

        @Schema(description = "List of authorized tool names")
        List<String> tools,

        @Schema(description = "List of subscribed trigger names")
        List<String> triggers,

        @Schema(description = "Webhook URL (required when triggersTo is 'webhook')")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Agentic team public ID")
        UUID agenticTeamPubId
) {
}
