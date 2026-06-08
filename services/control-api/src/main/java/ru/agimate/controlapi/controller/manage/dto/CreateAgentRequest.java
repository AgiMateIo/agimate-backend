package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.AgentType;

import java.util.UUID;

@Schema(description = "Request to create an agent")
public record CreateAgentRequest(
        @NotNull
        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Agent type: CENTRIFUGO, WEBHOOK or GENERIC")
        AgentType type,

        @Schema(description = "Webhook URL (required when type is WEBHOOK)")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Agentic team public ID")
        UUID agenticTeamId
) {
}
