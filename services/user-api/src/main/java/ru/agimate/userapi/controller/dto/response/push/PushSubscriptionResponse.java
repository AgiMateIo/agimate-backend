package ru.agimate.userapi.controller.dto.response.push;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.service.push.PushTokens;

import java.time.LocalDateTime;

/** What a device is subscribed with, shown next to the sign-in that registered it. */
@Schema(description = "A device's standing subscription to notifications")
public record PushSubscriptionResponse(
        @Schema(description = "Transport the token belongs to", example = "RUSTORE")
        PushProvider provider,

        @Schema(description = "First characters of the token — enough to tell it from the one the "
                + "application holds. The token itself is the right to notify the device and is never "
                + "returned", example = "cV8kQz1p…")
        String maskedToken,

        @Schema(description = "When the device last registered or refreshed this token")
        LocalDateTime lastSeenAt
) {

    public static PushSubscriptionResponse of(PushSubscription subscription) {
        return new PushSubscriptionResponse(
                subscription.getProvider(),
                PushTokens.masked(subscription.getToken()),
                subscription.getLastSeenAt());
    }
}
