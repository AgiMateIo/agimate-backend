package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to update an app")
public record UpdateAppRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the app")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Enable or disable the app")
        Boolean enabled
) {}
