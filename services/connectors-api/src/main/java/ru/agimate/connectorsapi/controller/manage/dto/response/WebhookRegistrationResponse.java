package ru.agimate.connectorsapi.controller.manage.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.Webhook;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Webhook registration information")
public record WebhookRegistrationResponse(
        @Schema(description = "Public ID of the webhook registration")
        UUID id,

        @Schema(description = "Webhook name/label")
        String name,

        @Schema(description = "Webhook description")
        String description,

        @Schema(description = "Event types subscribed to")
        List<String> eventTypes,

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
    public static WebhookRegistrationResponse from(Webhook webhook) {
        List<String> eventTypes = webhook.getEvents().stream()
                .map(e -> e.getEventType())
                .sorted()
                .toList();

        return new WebhookRegistrationResponse(
                webhook.getPubId(),
                webhook.getName(),
                webhook.getDescription(),
                eventTypes,
                webhook.getUrl(),
                webhook.hasAuth(),
                webhook.getEnabled(),
                webhook.getLastTriggeredAt(),
                webhook.getCreatedAt(),
                webhook.getUpdatedAt()
        );
    }
}
