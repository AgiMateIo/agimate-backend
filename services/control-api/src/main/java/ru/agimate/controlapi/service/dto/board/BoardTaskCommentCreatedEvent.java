package ru.agimate.controlapi.service.dto.board;

import java.util.UUID;

public record BoardTaskCommentCreatedEvent(
        UUID boardId,
        UUID taskId,
        UUID commentId,
        UUID agentId,
        String content
) {
}
