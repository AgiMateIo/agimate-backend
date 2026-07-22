package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRunProjection;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Trigger delivered to an agent + that agent's run")
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

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last run activity timestamp")
        LocalDateTime lastActivityAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the agent received the trigger")
        LocalDateTime createdAt
) {
    public static AgentRunResponse from(AgentRunProjection p) {
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
                p.getLastActivityAt(),
                p.getCreatedAt()
        );
    }
}
