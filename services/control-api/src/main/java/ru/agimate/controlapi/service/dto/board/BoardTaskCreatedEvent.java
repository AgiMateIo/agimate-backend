package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;

import java.util.UUID;

public record BoardTaskCreatedEvent(
        UUID boardId,
        UUID taskId,
        BoardTaskType type,
        BoardTaskStatus status,
        String title,
        String description,
        UUID createdByAgentId,
        UUID assigneeAgentId,
        UUID parentTaskId
) {
}
