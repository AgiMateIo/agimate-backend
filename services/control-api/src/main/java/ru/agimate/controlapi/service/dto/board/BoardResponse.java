package ru.agimate.controlapi.service.dto.board;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Board information")
public record BoardResponse(
        @Schema(description = "Board public ID")
        UUID id,

        @Schema(description = "Board name")
        String name,

        @Schema(description = "Board description")
        String description,

        @Schema(description = "Agentic team public ID")
        UUID agenticTeamId,

        @Schema(description = "Agentic team name")
        String agenticTeamName,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt
) {
    public static BoardResponse from(Board board, AgenticTeam team) {
        return new BoardResponse(
                board.getId(),
                board.getName(),
                board.getDescription(),
                team.getId(),
                team.getName(),
                board.getCreatedAt(),
                board.getUpdatedAt()
        );
    }
}
