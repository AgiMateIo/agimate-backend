package ru.agimate.controlapi.service.dto.board;

import java.util.UUID;

/** Команда создания доски (вход {@code BoardService}, не зависит от транспорта). */
public record BoardCreateCommand(
        UUID agenticTeamId,
        String name,
        String description
) {}
