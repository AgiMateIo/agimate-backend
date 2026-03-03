package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.agimate.deviceapi.database.enums.BoardTaskType;

import java.util.UUID;

@Schema(description = "Request to create a board task")
public record CreateBoardTaskRequest(
        @NotNull
        @Schema(description = "Task type")
        BoardTaskType type,

        @NotBlank
        @Size(min = 1, max = 500)
        @Schema(description = "Task title")
        String title,

        @Size(max = 5000)
        @Schema(description = "Task description")
        String description,

        @NotNull
        @Schema(description = "Agent who creates the task (must be in the board's agentic team)")
        UUID createdByAgentPubId,

        @Schema(description = "Agent assigned to the task (must be in the board's agentic team)")
        UUID assigneeAgentPubId,

        @Schema(description = "Parent task public ID (for hierarchy: epic -> task -> subtask)")
        UUID parentTaskPubId
) {}
