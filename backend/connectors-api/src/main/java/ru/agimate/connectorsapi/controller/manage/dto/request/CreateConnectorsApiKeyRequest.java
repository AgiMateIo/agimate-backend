package ru.agimate.connectorsapi.controller.manage.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateConnectorsApiKeyRequest(
        @NotBlank String name,
        String description
) {}
