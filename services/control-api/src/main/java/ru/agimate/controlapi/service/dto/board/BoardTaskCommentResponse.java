package ru.agimate.controlapi.service.dto.board;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.BoardTaskComment;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Board task comment information")
public record BoardTaskCommentResponse(
        @Schema(description = "Comment public ID")
        UUID id,

        @Schema(description = "Agent public ID")
        UUID agentId,

        @Schema(description = "Comment content")
        String content,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static BoardTaskCommentResponse from(BoardTaskComment comment, UUID agentId) {
        return new BoardTaskCommentResponse(
                comment.getId(),
                agentId,
                comment.getContent(),
                comment.getCreatedAt()
        );
    }
}
