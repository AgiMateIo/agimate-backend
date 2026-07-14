package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.UUID;

/** Команда смены статуса задачи (вход {@code BoardService}, не зависит от транспорта). */
public record BoardTaskStatusChangeCommand(
        BoardTaskStatus status,
        UUID agentId
) {}
