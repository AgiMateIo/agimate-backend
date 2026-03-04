package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create a new connector")
public record CreateConnectorRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the connector", example = "My Home Device")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description", example = "Connector for living room automation")
        String description,

        @NotBlank
        @Schema(description = "Connector registry code")
        String connectorCode
) {}
