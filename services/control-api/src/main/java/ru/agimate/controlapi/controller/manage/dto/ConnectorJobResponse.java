package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Background connector task")
public record ConnectorJobResponse(
        @Schema(description = "Job ID")
        UUID id,

        @Schema(description = "Job origin: SYSTEM (declared by connector), USER, AGENT")
        ConnectorJobKind kind,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector instance identity (integration credentials id, board pubId, ...)")
        String identity,

        @Schema(description = "Initiating (or target) agent ID; null for SYSTEM tasks")
        UUID agentId,

        @Schema(description = "Job name dispatched to the connector")
        String name,

        @Schema(description = "Schedule type")
        ConnectorJobType type,

        @Schema(description = "Schedule parameters (intervalSeconds / cron + zone)")
        Map<String, Object> config,

        @Schema(description = "Arguments passed to the task on each run")
        Map<String, Object> args,

        @Schema(description = "Scheduler state")
        ConnectorJobStatus status,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Next scheduled run; null for COMPLETED")
        LocalDateTime nextRunAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the task was paused by the user; null if active")
        LocalDateTime pausedAt,

        @Schema(description = "Error of the latest iteration; null on success")
        String lastError,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the task was created")
        LocalDateTime createdAt
) {
    public static ConnectorJobResponse from(ConnectorJob task) {
        return new ConnectorJobResponse(
                task.getId(),
                task.getKind(),
                task.getConnectorCode(),
                task.getIdentity(),
                task.getAgentId(),
                task.getName(),
                task.getType(),
                task.getConfig(),
                task.getArgs(),
                task.getStatus(),
                task.getNextRunAt(),
                task.getPausedAt(),
                task.getLastError(),
                task.getCreatedAt()
        );
    }
}
