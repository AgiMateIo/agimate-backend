package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.BoardTask;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Board task information")
public record BoardTaskResponse(
        @Schema(description = "Task public ID")
        UUID id,

        @Schema(description = "Task type")
        BoardTaskType type,

        @Schema(description = "Task status")
        BoardTaskStatus status,

        @Schema(description = "Task title")
        String title,

        @Schema(description = "Task description")
        String description,

        @Schema(description = "Created by agent public ID")
        UUID createdByAgentId,

        @Schema(description = "Assignee agent public ID")
        UUID assigneeAgentId,

        @Schema(description = "Parent task public ID")
        UUID parentTaskId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt
) {
    public static BoardTaskResponse from(BoardTask task, UUID createdByAgentId, UUID assigneeAgentId, UUID parentTaskId) {
        return new BoardTaskResponse(
                task.getId(),
                task.getType(),
                task.getStatus(),
                task.getTitle(),
                task.getDescription(),
                createdByAgentId,
                assigneeAgentId,
                parentTaskId,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
