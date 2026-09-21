package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A request one agent handed to another with {@code ask_agent}. One row of the team's requests and
 * the header of a single one: the same fields, so a listing and an opened request cannot disagree.
 * What was asked and what came back in full is the exchange of {@link AgentRequestDetailResponse}.
 */
@Schema(description = "A request from one team agent to another")
public record AgentRequestResponse(
        @Schema(description = "Request id — the callee's thread, the same threadId ask_agent returns")
        UUID id,

        @Schema(description = "Title of the first request of the thread")
        String title,

        @Schema(description = "The agent that asked")
        AgentRequestParty from,

        @Schema(description = "The agent that was asked")
        AgentRequestParty to,

        @Schema(description = "The conversation the request was born in, and where the report went back")
        AgentRequestOrigin origin,

        @Schema(description = "State folded from the thread's runs")
        AgentRequestStatus status,

        @Schema(description = "How many requests the thread carries — the first one plus everything added to it")
        int requestsCount,

        @Schema(description = "The last thing the callee answered; null while it has answered nothing")
        AgentRequestReport lastReport,

        @Schema(description = "When the request was made")
        LocalDateTime createdAt,

        @Schema(description = "Last activity in the thread")
        LocalDateTime lastActivityAt,

        @Schema(description = "Set when the thread is closed — nothing can be added to it any more")
        LocalDateTime closedAt
) {
}
