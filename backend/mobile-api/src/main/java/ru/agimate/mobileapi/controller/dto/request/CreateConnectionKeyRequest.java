package ru.agimate.mobileapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "Request to create a new connection key")
public record CreateConnectionKeyRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the key", example = "My Home Device")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description", example = "API key for living room automation")
        String description,

        @Schema(description = "Rate limit: requests per hour (null = unlimited)", example = "1000")
        Integer requestsPerHour,

        @Schema(description = "Optional expiration date")
        LocalDateTime expiresAt,

        @Schema(description = "Comma-separated IP whitelist (null = allow all)", example = "192.168.1.1,192.168.1.2")
        String ipWhitelist
) {}
