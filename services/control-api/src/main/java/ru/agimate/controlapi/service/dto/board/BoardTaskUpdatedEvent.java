package ru.agimate.controlapi.service.dto.board;

import java.util.List;
import java.util.UUID;

/** Centrifugo-событие правки полей задачи (не статуса): фронт перечитывает карточку. */
public record BoardTaskUpdatedEvent(
        UUID boardId,
        UUID taskId,
        List<String> changedFields
) {}
