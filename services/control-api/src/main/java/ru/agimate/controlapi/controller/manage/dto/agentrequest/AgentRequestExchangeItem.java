package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One turn of a request thread, read as a conversation: what was asked, what came back. Both kinds
 * share one shape with the other half left null — a client renders a list in time order, and a
 * union of two records would make that harder, not clearer.
 */
@Schema(description = "A request or a report in a thread")
public record AgentRequestExchangeItem(
        @Schema(description = "REQUEST or REPORT")
        AgentRequestExchangeKind kind,

        @Schema(description = "When it happened")
        LocalDateTime at,

        @Schema(description = "The callee's run — GET /manage/runs/{runId}/turns/ shows what it did")
        UUID runId,

        @Schema(description = "REQUEST: new for the first request of the thread, append for one added to it")
        String mode,

        @Schema(description = "REQUEST: title of the request")
        String title,

        @Schema(description = "REQUEST: what to do and what to answer")
        String instructions,

        @Schema(description = "REQUEST: what the callee was told about the conversation; null when none was passed")
        String context,

        @Schema(description = "REQUEST: the request was added to a thread that was already working, so "
                + "the run that was already going answers it — this one has no report of its own")
        Boolean absorbed,

        @Schema(description = "REPORT: DONE or FAILED")
        AgentRequestStatus status,

        @Schema(description = "REPORT: the answer, or the error")
        String text,

        @Schema(description = "REPORT: when the report reached the conversation; null means it never did")
        LocalDateTime reportedAt
) {

    public static AgentRequestExchangeItem request(LocalDateTime at, UUID runId, String mode, String title,
                                                   String instructions, String context, boolean absorbed) {
        return new AgentRequestExchangeItem(AgentRequestExchangeKind.REQUEST, at, runId, mode, title,
                instructions, context, absorbed, null, null, null);
    }

    public static AgentRequestExchangeItem report(LocalDateTime at, UUID runId, AgentRequestStatus status,
                                                  String text, LocalDateTime reportedAt) {
        return new AgentRequestExchangeItem(AgentRequestExchangeKind.REPORT, at, runId, null, null,
                null, null, null, status, text, reportedAt);
    }
}
