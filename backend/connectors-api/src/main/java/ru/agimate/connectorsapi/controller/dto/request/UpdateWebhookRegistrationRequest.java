package ru.agimate.connectorsapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to update a webhook registration")
public record UpdateWebhookRegistrationRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the webhook")
        String name,

        @Size(max = 500)
        @Schema(description = "Description")
        String description,

        @Pattern(regexp = "^[a-z0-9_]+(\\.[a-z0-9_]+)+$", message = "Event type must be in format: source.resource.action")
        @Schema(description = "Event type to subscribe to")
        String eventType,

        @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
        @Size(max = 2000)
        @Schema(description = "Webhook endpoint URL")
        String url,

        @Size(max = 1000)
        @Schema(description = "Authentication header (e.g., Bearer token)")
        String authHeader,

        @Schema(description = "Whether the webhook is enabled")
        Boolean enabled
) {
}
