package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** What the callee last answered, as a listing shows it. */
@Schema(description = "Preview of the callee's last answer")
public record AgentRequestReport(
        @Schema(description = "DONE or FAILED")
        AgentRequestStatus status,

        @Schema(description = "Beginning of the answer or of the error, truncated for the listing")
        String preview,

        @Schema(description = "When the callee finished")
        LocalDateTime at,

        @Schema(description = "When the report reached the conversation; null means the answer is "
                + "there but never got back to the agent that asked")
        LocalDateTime reportedAt
) {
}
