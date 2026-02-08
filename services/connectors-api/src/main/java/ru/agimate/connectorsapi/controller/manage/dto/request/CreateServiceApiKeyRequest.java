package ru.agimate.connectorsapi.controller.manage.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateServiceApiKeyRequest(
        @NotBlank String name,
        String description
) {}
