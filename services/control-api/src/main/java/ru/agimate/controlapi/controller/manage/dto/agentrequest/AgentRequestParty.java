package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A side of a request. The name is carried along: a listing of requests is read by names, not ids. */
@Schema(description = "An agent taking part in a request")
public record AgentRequestParty(
        @Schema(description = "Agent id")
        UUID agentId,

        @Schema(description = "Agent name; null when the agent is gone")
        String agentName
) {
}
