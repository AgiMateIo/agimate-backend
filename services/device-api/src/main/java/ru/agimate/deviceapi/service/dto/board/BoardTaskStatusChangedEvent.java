package ru.agimate.deviceapi.service.dto.board;

import ru.agimate.deviceapi.database.enums.BoardTaskStatus;

import java.util.UUID;

public record BoardTaskStatusChangedEvent(
        UUID boardId,
        UUID taskId,
        BoardTaskStatus oldStatus,
        BoardTaskStatus newStatus
) {
}
