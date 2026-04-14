package ru.agimate.deviceapi.service.dto.board;

import java.util.UUID;

public record BoardTaskCommentCreatedEvent(
        UUID boardPubId,
        UUID taskPubId,
        UUID commentPubId,
        UUID agentPubId,
        String content
) {
}
