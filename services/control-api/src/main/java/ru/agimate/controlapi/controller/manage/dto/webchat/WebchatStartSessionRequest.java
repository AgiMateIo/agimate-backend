package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Start a new webchat session with an agent")
public record WebchatStartSessionRequest(
        @NotNull
        @Schema(description = "Agent to chat with")
        UUID agentId
) {
}
