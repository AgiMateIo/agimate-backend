package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to create an agentic team")
public record CreateAgenticTeamRequest(
        @NotBlank
        @Schema(description = "Team name")
        String name,

        @Schema(description = "Team description")
        String description
) {
}
