package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One-line preview of a conversation for the listings. Progress lines are never the preview — the
 * row must show what the conversation ended on, not what the agent was doing halfway through it.
 */
@Schema(description = "Preview of the last message of a conversation")
public record WebchatLastMessage(
        @Schema(description = "Message text, truncated for the listing; null when the message carried attachments only")
        String text,

        @Schema(description = "USER or AGENT")
        String direction,

        @Schema(description = "Whether the message carried attachments — a row with no text is not an empty row")
        boolean hasAttachments,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
}
