package ru.agimate.deviceapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to update a device auth key")
public record UpdateDeviceAuthKeyRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the key")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Enable or disable the key")
        Boolean enabled
) {}
