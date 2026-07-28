package ru.agimate.controlapi.service.dto.board;

import java.util.UUID;

/** Command for creating a comment on a task (the input of {@code BoardService}, transport-independent). */
public record BoardTaskCommentCreateCommand(
        UUID agentId,
        String content
) {}
