package ru.agimate.deviceapi.service.dto.board;

import ru.agimate.deviceapi.database.enums.BoardTaskStatus;

import java.util.UUID;

public record BoardTaskStatusChangedEvent(
        UUID boardPubId,
        UUID taskPubId,
        BoardTaskStatus oldStatus,
        BoardTaskStatus newStatus
) {
}
