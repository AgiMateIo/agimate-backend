package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentSession;

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
        LocalDateTime createdAt,

        @Schema(description = "Agent messages past the read pointer; progress lines do not count")
        long unreadCount,

        @Schema(description = "Preview of the last message; null when nothing has been said yet")
        WebchatLastMessage lastMessage,

        @Schema(description = "Whether the agent is working in this session right now")
        boolean isRunning
) {

    /** A session on its own — creation and closing, where there is no listing to enrich it from. */
    public static WebchatSessionResponse from(AgentSession session, UUID agentId) {
        return from(session, agentId, 0, null, false);
    }

    public static WebchatSessionResponse from(AgentSession session, UUID agentId, long unreadCount,
                                              WebchatLastMessage lastMessage, boolean isRunning) {
        return new WebchatSessionResponse(
                session.getId(),
                session.getChannelId(),
                agentId,
                session.getTitle(),
                session.getLastActivityAt(),
                session.getClosedAt(),
                session.getCreatedAt(),
                unreadCount,
                lastMessage,
                isRunning);
    }
}
