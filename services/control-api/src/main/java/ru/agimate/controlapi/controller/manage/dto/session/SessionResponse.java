package ru.agimate.controlapi.controller.manage.dto.session;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentSession;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A conversation with an agent, whatever channel carries it. The three marks a chat list needs —
 * unread, preview, «working now» — are filled from the webchat UI log, so a session of another
 * connector reports zero/null there: an external messenger keeps its own unread state, not ours.
 */
@Schema(description = "Agent session")
public record SessionResponse(
        @Schema(description = "Session id (subscribe to Centrifugo channel webchat:{id} for a webchat session)")
        UUID id,

        @Schema(description = "Agent of this conversation")
        UUID agentId,

        @Schema(description = "Channel carrying the conversation; null for a session that belongs to a connection")
        UUID channelId,

        @Schema(description = "Connector the conversation runs over")
        String connectorCode,

        @Schema(description = "Title — derived from the first message, or set explicitly")
        String title,

        @Schema(description = "Last activity: a message, or a trigger routed into the session")
        LocalDateTime lastActivityAt,

        @Schema(description = "Set when the session is closed")
        LocalDateTime closedAt,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Agent messages past the read pointer; progress lines do not count")
        long unreadCount,

        @Schema(description = "Preview of the last message; null when nothing has been said yet")
        SessionLastMessage lastMessage,

        @Schema(description = "Whether the agent is working in this session right now")
        boolean isRunning
) {

    /** A session on its own — creation, renaming, closing, where there is no listing to enrich it from. */
    public static SessionResponse from(AgentSession session) {
        return from(session, 0, null, false);
    }

    public static SessionResponse from(AgentSession session, long unreadCount,
                                       SessionLastMessage lastMessage, boolean isRunning) {
        return new SessionResponse(
                session.getId(),
                session.getAgentId(),
                session.getChannelId(),
                session.getConnectorCode(),
                session.getTitle(),
                session.getLastActivityAt(),
                session.getClosedAt(),
                session.getCreatedAt(),
                unreadCount,
                lastMessage,
                isRunning);
    }
}
