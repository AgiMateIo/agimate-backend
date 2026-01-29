package ru.agimate.mobileapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Centrifugo subscription token response")
public record CentrifugoTokenResponse(
        @Schema(description = "JWT subscription token for Centrifugo channel")
        String token,

        @Schema(description = "Channel name the token is valid for")
        String channel
) {
}
