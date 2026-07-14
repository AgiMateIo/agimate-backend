package ru.agimate.controlapi.service.dto.board;

import java.util.UUID;

/** Команда создания комментария к задаче (вход {@code BoardService}, не зависит от транспорта). */
public record BoardTaskCommentCreateCommand(
        UUID agentId,
        String content
) {}
