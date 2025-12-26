package ru.agimate.connectorsapi.controller.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateConnectorsApiKeyRequest(
        @NotBlank String name,
        String description
) {}
