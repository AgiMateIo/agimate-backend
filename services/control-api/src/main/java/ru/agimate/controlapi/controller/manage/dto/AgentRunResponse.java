package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRunProjection;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A run of an agent + the trigger event that produced it")
public record AgentRunResponse(
        @Schema(description = "Run ID (agent_runs.id)")
        UUID id,

        @Schema(description = "Trigger log ID (the shared inbound event)")
        UUID triggerLogId,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector instance id (connections.id)")
        String connectionId,

        @Schema(description = "Trigger ID")
        String externalId,

        @Schema(description = "Trigger name")
        String name,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the trigger occurred")
        LocalDateTime occurredAt,

        @Schema(description = "Trigger input")
        Map<String, Object> input,

        @Schema(description = "Run status")
        RunStatus status,

        @Schema(description = "Run result")
        String result,

        @Schema(description = "Run error")
        String error,

        @Schema(description = "Channel session this run writes to (null for non-channel runs)")
        UUID sessionId,

        @Schema(description = "For a STEERED run — the run that absorbed and answered its message; "
                + "null otherwise. A steered run has no turns, prompt or result of its own")
        UUID mainRunId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the absorbing run confirmed the model saw this run's message; "
                + "set before the run turns STEERED — an ENQUEUED run with this field is already "
                + "being handled by mainRunId")
        LocalDateTime steeredAt,

        @Schema(description = "Whether the run's turn ledger is intact. False means the transcript has "
                + "a hole, and the run is left out of the history later runs are given")
        boolean turnsIntact,

        @Schema(description = "How many turns the run recorded — the size of its transcript")
        long turnsCount,

        @Schema(description = "Whether the run's input snapshot was taken; false for a run that never "
                + "reached the loop, or one older than the feature")
        boolean hasPrompt,

        @Schema(description = "What the run spent on the model; zeros for a run that never called it")
        RunUsageResponse usage,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last run activity timestamp")
        LocalDateTime lastActivityAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the agent received the trigger")
        LocalDateTime createdAt
) {
    public static AgentRunResponse from(AgentRunProjection p, RunUsageResponse usage) {
        return new AgentRunResponse(
                p.getId(),
                p.getTriggerLogId(),
                p.getConnectorCode(),
                p.getConnectionId(),
                p.getExternalId(),
                p.getName(),
                p.getOccurredAt(),
                p.getInput(),
                p.getStatus(),
                p.getResult(),
                p.getError(),
                p.getSessionId(),
                p.getMainRunId(),
                p.getSteeredAt(),
                p.getTurnsIntact(),
                p.getTurnsCount(),
                p.getHasPrompt(),
                usage,
                p.getLastActivityAt(),
                p.getCreatedAt()
        );
    }
}
