package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create a new app key. The app names itself at linking (code/title/version)")
public record CreateAppRequest(
        @Size(max = 500)
        @Schema(description = "Optional note about the key", example = "Living room automation")
        String description,

        @NotBlank
        @Schema(description = "Connector code")
        String connectorCode
) {}
