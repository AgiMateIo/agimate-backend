package ru.agimate.deviceapi.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Agent configuration for API key")
public record AgentConfigResponse(
        @Schema(description = "API key public ID")
        UUID apiKeyPubId,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Authorized tool names")
        List<String> tools,

        @Schema(description = "Subscribed trigger names")
        List<String> triggers
) {
}
