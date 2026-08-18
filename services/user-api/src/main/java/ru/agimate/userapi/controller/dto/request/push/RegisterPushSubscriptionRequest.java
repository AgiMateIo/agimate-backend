package ru.agimate.userapi.controller.dto.request.push;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registering this device for push notifications. The sign-in behind the subscription is taken from
 * the access token ({@code asid}), not from here: from the body it would be unverified, and what the
 * revocation removes hangs on it.
 */
@Schema(description = "Register or refresh this device's push subscription")
public record RegisterPushSubscriptionRequest(

        @Schema(description = "Transport the token belongs to, as the SDK names it: rustore | firebase | hms",
                example = "rustore")
        @NotBlank
        String provider,

        @Schema(description = "Push token issued to this installation by the transport")
        @NotBlank
        @Size(max = 4096)
        String token
) {
}
