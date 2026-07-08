package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.ChannelSession;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Webchat session")
public record WebchatSessionResponse(
        @Schema(description = "Session id (subscribe to Centrifugo channel webchat:{sessionId})")
        UUID sessionId,

        @Schema(description = "Underlying channel id")
        UUID channelId,

        @Schema(description = "Agent of this chat")
        UUID agentId,

        @Schema(description = "Title derived from the first message")
        String title,

        @Schema(description = "Last activity timestamp")
        LocalDateTime lastMessageAt,

        @Schema(description = "Set when the session is closed")
        LocalDateTime closedAt,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {

    public static WebchatSessionResponse from(ChannelSession session, UUID agentId) {
        return new WebchatSessionResponse(
                session.getId(),
                session.getChannelId(),
                agentId,
                session.getTitle(),
                session.getLastMessageAt(),
                session.getClosedAt(),
                session.getCreatedAt());
    }
}
