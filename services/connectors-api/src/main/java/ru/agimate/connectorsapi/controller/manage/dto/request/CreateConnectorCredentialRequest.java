package ru.agimate.connectorsapi.controller.manage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

@Schema(description = "Request to create a new connector credential")
public record CreateConnectorCredentialRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(description = "Name/label for the credential", example = "My Ozon Store")
        String name,

        @Size(max = 500)
        @Schema(description = "Optional description")
        String description,

        @NotEmpty
        @Schema(description = "Credential data (API keys, tokens, etc.)")
        Map<String, String> data
) {}
