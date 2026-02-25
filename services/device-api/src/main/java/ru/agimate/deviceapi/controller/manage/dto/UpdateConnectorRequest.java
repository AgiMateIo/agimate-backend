package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to update a connector")
public record UpdateConnectorRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the connector")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Enable or disable the connector")
        Boolean enabled
) {}
