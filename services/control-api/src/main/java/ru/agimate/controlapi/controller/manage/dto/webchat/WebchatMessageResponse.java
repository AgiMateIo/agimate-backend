package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.service.webchat.WebchatAttachment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Message of a webchat session (UI history)")
public record WebchatMessageResponse(
        @Schema(description = "Row id")
        UUID id,

        @Schema(description = "Delivery message id (deduplication key, matches Centrifugo events)")
        String messageId,

        @Schema(description = "USER or AGENT")
        String direction,

        @Schema(description = "Agent output stream: answer/progress/error; null for USER messages")
        String stream,

        @Schema(description = "Message text")
        String text,

        @Schema(description = "Attachments with fresh signed content URLs; null when the message has none")
        List<WebchatAttachment> parts,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {

    public static WebchatMessageResponse from(WebchatMessage message, List<WebchatAttachment> parts) {
        return new WebchatMessageResponse(
                message.getId(),
                message.getMessageId(),
                message.getDirection().name(),
                message.getStream(),
                message.getText(),
                parts,
                message.getCreatedAt());
    }
}
