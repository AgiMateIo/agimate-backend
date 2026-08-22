package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.AgentType;

/**
 * The PATCH body: every field is optional and {@code null} means "not sent". Clearing is therefore
 * spelled as an empty string — the same convention {@code applyWebhookAuthHeader} has always used,
 * and the reason this endpoint needs no three-state wrapper on top of Jackson.
 */
@Schema(description = "Partial update of an agent: only the fields present in the body are written, "
        + "an empty string clears a field")
public record PatchAgentRequest(
        @Schema(description = "Agent name (an empty string is rejected — an agent always has a name)")
        String name,

        @Schema(description = "Agent description; an empty string clears it")
        String description,

        @Schema(description = "Agent instructions; an empty string clears them")
        String instructions,

        @Schema(description = "Agent type — where the brain lives: CENTRIFUGO, WEBHOOK, GENERIC or MCP. "
                + "Switching away from WEBHOOK clears webhookUrl and webhookAuthHeader")
        AgentType type,

        @Schema(description = "Webhook URL (required when the resulting type is WEBHOOK — either sent "
                + "here or already stored); an empty string clears it")
        String webhookUrl,

        @Schema(description = "Webhook authorization header value; an empty string clears it")
        String webhookAuthHeader,

        @Schema(description = "Whether the agent is enabled")
        Boolean enabled
) {
}
