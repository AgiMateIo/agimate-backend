package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.UUID;

/**
 * Команда правки задачи: {@code null}-поле = «не менять» (вход {@code BoardService}, не зависит
 * от транспорта). Claim-правило на assignee — в сервисе.
 */
public record BoardTaskEditCommand(
        UUID actorAgentId,
        String title,
        String description,
        UUID assigneeAgentId,
        BoardTaskStatus status
) {}
