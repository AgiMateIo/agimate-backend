package ru.agimate.mobileapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create a new device auth key")
public record CreateDeviceAuthKeyRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the key", example = "My Home Device")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description", example = "API key for living room automation")
        String description
) {}
