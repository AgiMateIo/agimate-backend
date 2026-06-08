package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgenticTeam;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Agentic team response")
public record AgenticTeamResponse(
        @Schema(description = "Team ID")
        UUID id,

        @Schema(description = "Team name")
        String name,

        @Schema(description = "Team description")
        String description,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the team was created")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the team was last updated")
        LocalDateTime updatedAt
) {
    public static AgenticTeamResponse from(AgenticTeam team) {
        return new AgenticTeamResponse(
                team.getId(),
                team.getName(),
                team.getDescription(),
                team.getCreatedAt(),
                team.getUpdatedAt()
        );
    }
}
