package ru.agimate.connectorsapi.controller.manage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;

@Schema(description = "Request to create a new webhook registration")
public record CreateWebhookRegistrationRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the webhook", example = "Production n8n webhook")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @NotNull
        @Size(min = 1, message = "At least one event type is required")
        @Schema(description = "Event types to subscribe to", example = "[\"ozon.order.created\", \"ozon.order.updated\"]")
        List<@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$", message = "Event type must be in format: source.resource.action") String> eventTypes,

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
