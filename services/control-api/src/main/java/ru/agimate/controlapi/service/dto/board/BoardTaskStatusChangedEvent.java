package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.UUID;

public record BoardTaskStatusChangedEvent(
        UUID boardId,
        UUID taskId,
        BoardTaskStatus oldStatus,
        BoardTaskStatus newStatus
) {
}
