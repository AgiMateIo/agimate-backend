package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.AgentType;

@Schema(description = "Request to update an agent")
public record UpdateAgentRequest(
        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Agent instructions")
        String instructions,

        @Schema(description = "Agent type — where the brain lives: CENTRIFUGO, WEBHOOK, GENERIC or MCP")
        AgentType type,

        @Schema(description = "Webhook URL (required when type is WEBHOOK)")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value")
        String webhookAuthHeader,

        @Schema(description = "Whether the agent is enabled")
        Boolean enabled
) {
}
