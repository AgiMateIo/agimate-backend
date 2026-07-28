package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskType;

import java.util.UUID;

/** Command for creating a task on a board (the input of {@code BoardService}, transport-independent). */
public record BoardTaskCreateCommand(
        BoardTaskType type,
        String title,
        String description,
        UUID createdByAgentId,
        UUID assigneeAgentId,
        UUID parentTaskId
) {}
