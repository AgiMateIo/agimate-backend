package ru.agimate.deviceapi.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request for agent Centrifugo token")
public record AgentCentrifugoTokenRequest(
        @NotNull
        @Schema(description = "API key public ID")
        UUID apiKeyPubId
) {
}
