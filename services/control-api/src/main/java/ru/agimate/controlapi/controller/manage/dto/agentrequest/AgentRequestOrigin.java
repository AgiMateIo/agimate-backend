package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where a request came from: the conversation of the agent that asked. The report went back into
 * this same conversation, so it is also the answer to «where did the reply land».
 */
@Schema(description = "The conversation a request was born in")
public record AgentRequestOrigin(
        @Schema(description = "Session id of the conversation — GET /manage/sessions/{id}")
        UUID sessionId,

        @Schema(description = "Title of the conversation")
        String title,

        @Schema(description = "Connector carrying the conversation: webchat, telegram, …")
        String connectorCode
) {
}
