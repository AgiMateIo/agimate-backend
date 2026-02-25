package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.BoardTask;
import ru.agimate.deviceapi.database.entities.BoardTaskStatus;
import ru.agimate.deviceapi.database.entities.BoardTaskType;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Board task information")
public record BoardTaskResponse(
        @Schema(description = "Task public ID")
        UUID pubId,

        @Schema(description = "Task type")
        BoardTaskType type,

        @Schema(description = "Task status")
        BoardTaskStatus status,

        @Schema(description = "Task title")
        String title,

        @Schema(description = "Task description")
        String description,

        @Schema(description = "Created by agent public ID")
        UUID createdByAgentPubId,

        @Schema(description = "Assignee agent public ID")
        UUID assigneeAgentPubId,

        @Schema(description = "Parent task public ID")
        UUID parentTaskPubId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt
) {
    public static BoardTaskResponse from(BoardTask task, UUID createdByAgentPubId, UUID assigneeAgentPubId, UUID parentTaskPubId) {
        return new BoardTaskResponse(
                task.getPubId(),
                task.getType(),
                task.getStatus(),
                task.getTitle(),
                task.getDescription(),
                createdByAgentPubId,
                assigneeAgentPubId,
                parentTaskPubId,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
