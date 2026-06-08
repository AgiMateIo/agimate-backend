package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to update an agentic team")
public record UpdateAgenticTeamRequest(
        @NotBlank
        @Schema(description = "Team name")
        String name,

        @Schema(description = "Team description")
        String description
) {
}
