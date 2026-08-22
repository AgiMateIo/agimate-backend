package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Partial update of an agentic team: only the fields present in the body are "
        + "written, an empty string clears a field")
public record PatchAgenticTeamRequest(
        @Schema(description = "Team name (an empty string is rejected — a team always has a name)")
        String name,

        @Schema(description = "Team description; an empty string clears it")
        String description
) {
}
