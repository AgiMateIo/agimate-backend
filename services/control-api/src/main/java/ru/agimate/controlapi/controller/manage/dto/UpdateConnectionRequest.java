package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to update connection settings")
public record UpdateConnectionRequest(
        @Schema(description = "Enable or disable the connection")
        Boolean enabled,

        @Schema(description = "Connection name")
        String name
) {}
