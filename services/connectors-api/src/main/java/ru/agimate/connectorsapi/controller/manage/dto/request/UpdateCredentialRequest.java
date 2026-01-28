package ru.agimate.connectorsapi.controller.manage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.Map;

@Schema(description = "Request to update a credential")
public record UpdateCredentialRequest(
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the credential")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Updated credential data (optional)")
        Map<String, String> data,

        @Schema(description = "Enable or disable the credential")
        Boolean enabled
) {}
