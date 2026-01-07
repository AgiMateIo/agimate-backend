package ru.agimate.connectorsapi.controller.manage.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.WebhookRegistration;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Webhook registration information")
public record WebhookRegistrationResponse(
        @Schema(description = "Public ID of the webhook registration")
        UUID id,

        @Schema(description = "Webhook name/label")
        String name,

        @Schema(description = "Webhook description")
        String description,

        @Schema(description = "Event type subscribed to")
        String eventType,

        @Schema(description = "Webhook endpoint URL")
        String url,

        @Schema(description = "Whether authentication header is configured (actual value not exposed)")
        boolean hasAuth,

        @Schema(description = "Whether the webhook is enabled")
        boolean enabled,

        @Schema(description = "Last time the webhook was triggered")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastTriggeredAt,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime updatedAt
) {
    public static WebhookRegistrationResponse from(WebhookRegistration webhook) {
        return new WebhookRegistrationResponse(
                webhook.getPubId(),
                webhook.getName(),
                webhook.getDescription(),
                webhook.getEventType(),
                webhook.getUrl(),
                webhook.hasAuth(),
                webhook.getEnabled(),
                webhook.getLastTriggeredAt(),
                webhook.getCreatedAt(),
                webhook.getUpdatedAt()
        );
    }
}
