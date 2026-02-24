package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to update integration settings")
public record UpdateIntegrationRequest(
        @Schema(description = "Enable or disable the integration")
        Boolean enabled,

        @Schema(description = "Integration name")
        String name
) {}
