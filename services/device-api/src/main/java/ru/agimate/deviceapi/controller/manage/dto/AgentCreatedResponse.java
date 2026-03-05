package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Agent created response with API key (shown once)")
public record AgentCreatedResponse(
        @Schema(description = "Agent details")
        AgentResponse agent,

        @Schema(description = "Full API key (shown only once)")
        String fullKey
) {
}
