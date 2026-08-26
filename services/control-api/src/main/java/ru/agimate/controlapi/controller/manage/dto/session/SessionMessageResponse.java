package ru.agimate.controlapi.controller.manage.dto.session;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.webchat.WebchatAttachment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A message of a session's history. Two stores are folded into this one shape: the webchat UI log
 * ({@code webchat_messages}) and the dialogue record every other channel writes
 * ({@code channel_session_messages}). The caller is not told which one it read — the split is an
 * implementation detail of the channel, and the day it goes away no client should have to change.
 * A field the source cannot carry comes back null rather than invented.
 */
@Schema(description = "Message of a session's history")
public record SessionMessageResponse(
        @Schema(description = "Row id — what the read pointer addresses")
        UUID id,

        @Schema(description = "Delivery message id (deduplication key, matches Centrifugo events); "
                + "null outside webchat")
        String messageId,

        @Schema(description = "USER or AGENT")
        String direction,

        @Schema(description = "Agent output stream: answer/progress/error; null for USER messages")
        String stream,

        @Schema(description = "Message text")
        String text,

        @Schema(description = "Attachments with fresh signed content URLs; null when there are none")
        List<WebchatAttachment> parts,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {

    private static final String DIRECTION_USER = "USER";
    private static final String DIRECTION_AGENT = "AGENT";

    public static SessionMessageResponse from(WebchatMessage message, List<WebchatAttachment> parts) {
        return new SessionMessageResponse(
                message.getId(),
                message.getMessageId(),
                message.getDirection().name(),
                message.getStream(),
                message.getText(),
                parts,
                message.getCreatedAt());
    }

    public static SessionMessageResponse from(ChannelSessionMessage message) {
        boolean inbound = message.getKind() == ChannelSessionMessageKind.INBOUND;
        return new SessionMessageResponse(
                message.getId(),
                null,
                inbound ? DIRECTION_USER : DIRECTION_AGENT,
                inbound ? null : message.getKind().name().toLowerCase(),
                message.getMessage(),
                null,
                message.getCreatedAt());
    }
}
