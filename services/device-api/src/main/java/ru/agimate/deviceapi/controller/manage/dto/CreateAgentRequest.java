package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.deviceapi.database.entities.TriggerDestination;

import java.util.UUID;

@Schema(description = "Request to create an agent")
public record CreateAgentRequest(
        @NotNull
        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Trigger destination: CENTRIFUGO or WEBHOOK")
        TriggerDestination triggerDestination,

        @Schema(description = "Webhook URL (required when triggerDestination is WEBHOOK)")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Agentic team public ID")
        UUID agenticTeamPubId
) {
}
