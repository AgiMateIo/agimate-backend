package ru.agimate.userapi.controller.dto.request.push;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Signing out on this device. The token travels in the body rather than in the query string on
 * purpose: a query lands in the access logs of everything on the way, and this token is the right to
 * notify a device.
 */
@Schema(description = "Remove this device's push subscription")
public record UnregisterPushSubscriptionRequest(

        @Schema(description = "The push token this device registered")
        @NotBlank
        @Size(max = 4096)
        String token
) {
}
