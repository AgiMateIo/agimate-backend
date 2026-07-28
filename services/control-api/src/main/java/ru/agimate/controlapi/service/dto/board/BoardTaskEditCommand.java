package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.UUID;

/**
 * Command for editing a task: a {@code null} field means «leave unchanged» (the input of
 * {@code BoardService}, transport-independent). The claim rule on assignee lives in the service.
 */
public record BoardTaskEditCommand(
        UUID actorAgentId,
        String title,
        String description,
        UUID assigneeAgentId,
        BoardTaskStatus status
) {}
