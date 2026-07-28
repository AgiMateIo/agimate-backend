package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.UUID;

/** Command for changing a task's status (the input of {@code BoardService}, transport-independent). */
public record BoardTaskStatusChangeCommand(
        BoardTaskStatus status,
        UUID agentId
) {}
