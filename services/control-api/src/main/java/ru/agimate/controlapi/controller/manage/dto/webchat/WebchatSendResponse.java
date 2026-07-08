package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Acknowledgement of an accepted webchat message")
public record WebchatSendResponse(
        @Schema(description = "Session the message was routed to")
        UUID sessionId,

        @Schema(description = "Id assigned to the user's message (echoed in Centrifugo events and history)")
        String messageId
) {
}
