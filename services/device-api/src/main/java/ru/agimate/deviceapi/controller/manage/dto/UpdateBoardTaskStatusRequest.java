package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.deviceapi.database.entities.BoardTaskStatus;

import java.util.UUID;

@Schema(description = "Request to change board task status")
public record UpdateBoardTaskStatusRequest(
        @NotNull
        @Schema(description = "New task status")
        BoardTaskStatus status,

        @NotNull
        @Schema(description = "Agent who changes the status (must be in the board's agentic team)")
        UUID agentPubId
) {}
