package ru.agimate.controlapi.service.dto.board;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.List;
import java.util.Map;

@Schema(description = "Board tasks grouped by status")
public record BoardTasksByStatusResponse(
        @Schema(description = "Tasks grouped by status")
        Map<BoardTaskStatus, List<BoardTaskResponse>> tasks
) {}
