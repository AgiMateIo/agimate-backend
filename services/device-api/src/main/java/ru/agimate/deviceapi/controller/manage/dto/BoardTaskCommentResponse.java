package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.BoardTaskComment;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Board task comment information")
public record BoardTaskCommentResponse(
        @Schema(description = "Comment public ID")
        UUID pubId,

        @Schema(description = "Agent public ID")
        UUID agentPubId,

        @Schema(description = "Comment content")
        String content,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static BoardTaskCommentResponse from(BoardTaskComment comment, UUID agentPubId) {
        return new BoardTaskCommentResponse(
                comment.getPubId(),
                agentPubId,
                comment.getContent(),
                comment.getCreatedAt()
        );
    }
}
