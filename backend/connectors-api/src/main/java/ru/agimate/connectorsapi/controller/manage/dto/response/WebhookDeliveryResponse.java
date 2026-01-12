package ru.agimate.connectorsapi.controller.manage.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.WebhookDelivery;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Webhook delivery information")
public record WebhookDeliveryResponse(
        @Schema(description = "Public ID of the delivery")
        UUID id,

        @Schema(description = "Event type that triggered the webhook")
        String eventType,

        @Schema(description = "Target URL that was called")
        String requestUrl,

        @Schema(description = "HTTP response status code")
        Integer responseStatusCode,

        @Schema(description = "Error message if delivery failed")
        String errorMessage,

        @Schema(description = "Request duration in milliseconds")
        Long durationMs,

        @Schema(description = "Whether the delivery was successful")
        boolean success,

        @Schema(description = "When the webhook was triggered")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime triggeredAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(
                delivery.getPubId(),
                delivery.getEventType(),
                delivery.getRequestUrl(),
                delivery.getResponseStatusCode(),
                delivery.getErrorMessage(),
                delivery.getDurationMs(),
                delivery.isSuccess(),
                delivery.getTriggeredAt()
        );
    }
}
