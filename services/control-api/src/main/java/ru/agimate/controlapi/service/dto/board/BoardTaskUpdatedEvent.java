package ru.agimate.controlapi.service.dto.board;

import java.util.List;
import java.util.UUID;

/** Centrifugo event for an edit of a task's fields (not its status): the frontend re-reads the card. */
public record BoardTaskUpdatedEvent(
        UUID boardId,
        UUID taskId,
        List<String> changedFields
) {}
