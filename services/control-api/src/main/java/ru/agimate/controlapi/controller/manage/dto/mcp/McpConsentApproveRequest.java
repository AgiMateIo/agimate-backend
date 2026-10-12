package ru.agimate.controlapi.controller.manage.dto.mcp;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Consent: the authorization request and the agent the client will act as")
public record McpConsentApproveRequest(
        @Schema(description = "The authorization request, unchanged")
        @NotNull @Valid
        McpAuthorizationParams authorization,

        @Schema(description = "An existing agent of the user; a new one is created beforehand with the usual endpoints")
        @NotNull
        UUID agentId
) {}
