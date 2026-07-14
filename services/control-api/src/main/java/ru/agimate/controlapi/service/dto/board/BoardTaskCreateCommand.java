package ru.agimate.controlapi.service.dto.board;

import ru.agimate.controlapi.database.enums.BoardTaskType;

import java.util.UUID;

/** Команда создания задачи на доске (вход {@code BoardService}, не зависит от транспорта). */
public record BoardTaskCreateCommand(
        BoardTaskType type,
        String title,
        String description,
        UUID createdByAgentId,
        UUID assigneeAgentId,
        UUID parentTaskId
) {}
