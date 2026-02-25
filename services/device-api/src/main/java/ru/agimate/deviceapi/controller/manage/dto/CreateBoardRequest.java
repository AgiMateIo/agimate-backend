package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request to create a board for an agentic team")
public record CreateBoardRequest(
        @NotNull
        @Schema(description = "Agentic team public ID")
        UUID agenticTeamPubId,

        @NotBlank
        @Size(min = 1, max = 200)
        @Schema(description = "Board name")
        String name,

        @Size(max = 1000)
        @Schema(description = "Board description")
        String description
) {}
