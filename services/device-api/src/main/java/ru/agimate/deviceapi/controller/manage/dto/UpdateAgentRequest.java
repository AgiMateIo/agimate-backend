package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.TriggerDestination;

@Schema(description = "Request to update an agent")
public record UpdateAgentRequest(
        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Trigger destination: CENTRIFUGO or WEBHOOK")
        TriggerDestination triggerDestination,

        @Schema(description = "Webhook URL (required when triggerDestination is WEBHOOK)")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Whether the agent is enabled")
        Boolean enabled
) {
}
