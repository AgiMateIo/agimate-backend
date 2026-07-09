package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.projections.TriggerLogWithAgentsCountProjection;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Trigger log entry")
public record TriggerLogResponse(
        @Schema(description = "Trigger log ID")
        UUID id,

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

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt,

        @Schema(description = "Number of agents that received this trigger")
        long agentsCount
) {
    public static TriggerLogResponse from(TriggerLogWithAgentsCountProjection projection) {
        return new TriggerLogResponse(
                projection.getId(),
                projection.getConnectorCode(),
                projection.getConnectionId(),
                projection.getExternalId(),
                projection.getName(),
                projection.getOccurredAt(),
                projection.getInput(),
                projection.getCreatedAt(),
                projection.getAgentsCount()
        );
    }

    public static TriggerLogResponse from(TriggerLog entity) {
        long agentsCount = entity.getTriggerLogAgents() == null ? 0 : entity.getTriggerLogAgents().size();
        return new TriggerLogResponse(
                entity.getId(),
                entity.getConnectorCode(),
                entity.getConnectionId(),
                entity.getExternalId(),
                entity.getName(),
                entity.getOccurredAt(),
                entity.getInput(),
                entity.getCreatedAt(),
                agentsCount
        );
    }
}
