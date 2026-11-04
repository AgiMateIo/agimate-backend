package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Partial update of an app: only the fields present in the body are written")
public record UpdateAppRequest(
        @Size(max = 500)
        @Schema(description = "Optional description; an empty string clears it")
        String description,

        @Schema(description = "Enable or disable the app")
        Boolean enabled
) {}
