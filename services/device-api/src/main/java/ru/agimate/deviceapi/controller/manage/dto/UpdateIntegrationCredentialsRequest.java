package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@Schema(description = "Request to update integration credentials")
public record UpdateIntegrationCredentialsRequest(
        @NotNull
        @Schema(description = "New platform credentials")
        Map<String, String> credentials
) {}
