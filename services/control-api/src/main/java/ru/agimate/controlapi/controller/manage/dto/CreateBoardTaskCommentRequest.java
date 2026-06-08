package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request to create a comment on a board task")
public record CreateBoardTaskCommentRequest(
        @NotNull
        @Schema(description = "Agent who creates the comment (must be in the board's agentic team)")
        UUID agentId,

        @NotBlank
        @Size(min = 1, max = 5000)
        @Schema(description = "Comment content")
        String content
) {}
