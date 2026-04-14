package ru.agimate.deviceapi.service.dto.board;

import ru.agimate.deviceapi.database.enums.BoardTaskStatus;
import ru.agimate.deviceapi.database.enums.BoardTaskType;

import java.util.UUID;

public record BoardTaskCreatedEvent(
        UUID boardPubId,
        UUID taskPubId,
        BoardTaskType type,
        BoardTaskStatus status,
        String title,
        String description,
        UUID createdByAgentPubId,
        UUID assigneeAgentPubId,
        UUID parentTaskPubId
) {
}
