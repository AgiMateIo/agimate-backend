package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.controller.manage.dto.session.SessionLastMessage;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A row of the messenger's contact list: an agent together with the state of its chat. Assembled
 * here rather than merged by the client from two listings — the order is by chat freshness, and
 * across pages that order cannot be restored after the fact.
 */
@Schema(description = "Agent as a contact: the agent plus the state of its webchat")
public record WebchatContactResponse(
        @Schema(description = "Agent id")
        UUID agentId,

        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Whether the agent is enabled — a disabled one still shows its history")
        boolean enabled,

        @Schema(description = "Unread agent messages across all conversations with this agent")
        long unreadCount,

        @Schema(description = "Preview of the last message; null when the chat has not started")
        SessionLastMessage lastMessage,

        @Schema(description = "Session the preview came from — the one to open on tap; null when there is none")
        UUID lastSessionId,

        @Schema(description = "Freshest activity across the agent's conversations; null when there are none")
        LocalDateTime lastActivityAt,

        @Schema(description = "Whether the agent is working in one of its chats right now")
        boolean isRunning
) {
}
