package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@Schema(description = "Request to create a new integration")
public record CreateIntegrationRequest(
        @NotBlank
        @Schema(description = "Platform code", example = "telegram")
        String platformCode,

        @NotNull
        @Schema(description = "Platform credentials", example = "{\"token\": \"123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11\"}")
        Map<String, String> credentials,

        @Schema(description = "Optional integration name", example = "My Telegram Bot")
        String name
) {}
