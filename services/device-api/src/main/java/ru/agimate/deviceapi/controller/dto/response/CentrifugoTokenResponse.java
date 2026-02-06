package ru.agimate.deviceapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Centrifugo tokens response")
public record CentrifugoTokenResponse(
        @Schema(description = "JWT connection token for Centrifugo WebSocket connection")
        String connectionToken,

        @Schema(description = "JWT subscription token for Centrifugo channel")
        String subscriptionToken,

        @Schema(description = "Channel name the subscription token is valid for")
        String channel
) {
}
