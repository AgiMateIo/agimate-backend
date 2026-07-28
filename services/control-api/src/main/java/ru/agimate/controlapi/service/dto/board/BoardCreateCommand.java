package ru.agimate.controlapi.service.dto.board;

import java.util.UUID;

/** Command for creating a board (the input of {@code BoardService}, transport-independent). */
public record BoardCreateCommand(
        UUID agenticTeamId,
        String name,
        String description
) {}
