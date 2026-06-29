package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@Schema(description = "Request to update connection secret (credential values)")
public record UpdateConnectionSecretRequest(
        @NotNull
        @Schema(description = "New platform credentials")
        Map<String, String> credentials
) {}
