package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create a new app")
public record CreateAppRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the app", example = "My Home Device")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description", example = "App for living room automation")
        String description,

        @NotBlank
        @Schema(description = "Connector code")
        String connectorCode
) {}
