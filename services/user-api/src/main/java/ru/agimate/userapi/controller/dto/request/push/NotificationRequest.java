package ru.agimate.userapi.controller.dto.request.push;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;
import java.util.UUID;

/**
 * What another service of ours asks to deliver to a person's devices. {@code data} is opaque here:
 * this service knows how to reach the devices, the caller knows what the notification means
 * (docs/decisions/push-notifications.md).
 *
 * @param ttlSeconds how long the transport should keep trying; null — the configured default. In
 *                   seconds rather than the transport's own duration format, which has no business
 *                   in a contract between our services
 */
@Schema(description = "Deliver a data notification to every device of a user")
public record NotificationRequest(

        @Schema(description = "Whose devices to notify")
        @NotNull
        UUID userId,

        @Schema(description = "Opaque key-value payload; at least one pair")
        @NotEmpty
        Map<String, String> data,

        @Schema(description = "Delivery lifetime in seconds; omit for the default")
        @Positive
        Integer ttlSeconds
) {
}
