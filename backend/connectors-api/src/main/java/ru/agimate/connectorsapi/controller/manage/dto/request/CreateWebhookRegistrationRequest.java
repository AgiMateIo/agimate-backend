package ru.agimate.connectorsapi.controller.manage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "Request to create a new webhook registration")
public record CreateWebhookRegistrationRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the webhook", example = "Production n8n webhook")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @NotBlank
        @Pattern(regexp = "^[a-z0-9_]+(\\.[a-z0-9_]+)+$", message = "Event type must be in format: source.resource.action")
        @Schema(description = "Event type to subscribe to", example = "ozon.order.created")
        String eventType,

        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
        @Size(max = 2000)
        @Schema(description = "Webhook endpoint URL", example = "https://n8n.example.com/webhook/abc-123")
        String url,

        @Size(max = 1000)
        @Schema(description = "Optional authentication header (e.g., Bearer token)", example = "Bearer xyz789")
        String authHeader,

        @Schema(description = "Whether the webhook is enabled", defaultValue = "true")
        Boolean enabled
) {
}
