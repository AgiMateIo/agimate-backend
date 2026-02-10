package ru.agimate.connectorsapi.controller.manage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Request to update a webhook registration")
public record UpdateWebhookRegistrationRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the webhook")
        String name,

        @Size(max = 500)
        @Schema(description = "Description")
        String description,

        @Size(min = 1, message = "At least one event type is required")
        @Schema(description = "Event types to subscribe to", example = "[\"ozon.order.created\", \"ozon.order.updated\"]")
        List<@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$", message = "Event type must be in format: source.resource.action") String> eventTypes,

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
