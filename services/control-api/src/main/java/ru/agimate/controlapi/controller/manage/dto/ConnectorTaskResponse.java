package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Background connector task")
public record ConnectorTaskResponse(
        @Schema(description = "Task ID")
        UUID id,

        @Schema(description = "Task origin: SYSTEM (declared by connector), USER, AGENT")
        ConnectorTaskKind kind,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector instance identity (integration credentials id, board pubId, ...)")
        String identity,

        @Schema(description = "Initiating (or target) agent ID; null for SYSTEM tasks")
        UUID agentId,

        @Schema(description = "Task name dispatched to the connector")
        String taskName,

        @Schema(description = "Schedule type")
        ConnectorTaskType taskType,

        @Schema(description = "Schedule parameters (intervalSeconds / cron + zone)")
        Map<String, Object> taskConfig,

        @Schema(description = "Arguments passed to the task on each run")
        Map<String, Object> taskArgs,

        @Schema(description = "Scheduler state")
        ConnectorTaskStatus status,

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
    public static ConnectorTaskResponse from(ConnectorTask task) {
        return new ConnectorTaskResponse(
                task.getId(),
                task.getKind(),
                task.getConnectorCode(),
                task.getIdentity(),
                task.getAgentId(),
                task.getTaskName(),
                task.getTaskType(),
                task.getTaskConfig(),
                task.getTaskArgs(),
                task.getStatus(),
                task.getNextRunAt(),
                task.getPausedAt(),
                task.getLastError(),
                task.getCreatedAt()
        );
    }
}
