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

        @Schema(description = "Identity")
        String identity,

        @Schema(description = "Trigger ID")
        String triggerId,

        @Schema(description = "Trigger name")
        String triggerName,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the trigger occurred")
        LocalDateTime occurredAt,

        @Schema(description = "Trigger input")
        Map<String, Object> triggerInput,

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
                projection.getIdentity(),
                projection.getTriggerId(),
                projection.getTriggerName(),
                projection.getOccurredAt(),
                projection.getTriggerInput(),
                projection.getCreatedAt(),
                projection.getAgentsCount()
        );
    }

    public static TriggerLogResponse from(TriggerLog entity) {
        long agentsCount = entity.getTriggerLogAgents() == null ? 0 : entity.getTriggerLogAgents().size();
        return new TriggerLogResponse(
                entity.getId(),
                entity.getConnectorCode(),
                entity.getIdentity(),
                entity.getTriggerId(),
                entity.getTriggerName(),
                entity.getOccurredAt(),
                entity.getTriggerInput(),
                entity.getCreatedAt(),
                agentsCount
        );
    }
}
