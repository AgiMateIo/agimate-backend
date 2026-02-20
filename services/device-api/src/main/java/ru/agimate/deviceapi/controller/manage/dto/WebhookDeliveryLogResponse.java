package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.WebhookDeliveryLog;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Webhook delivery log entry")
public record WebhookDeliveryLogResponse(
        @Schema(description = "Delivery log ID")
        UUID id,

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

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the webhook was delivered")
        LocalDateTime deliveredAt
) {
    public static WebhookDeliveryLogResponse from(WebhookDeliveryLog log) {
        return new WebhookDeliveryLogResponse(
                log.getPubId(),
                log.getRequestUrl(),
                log.getResponseStatusCode(),
                log.getErrorMessage(),
                log.getDurationMs(),
                log.isSuccess(),
                log.getDeliveredAt()
        );
    }
}
