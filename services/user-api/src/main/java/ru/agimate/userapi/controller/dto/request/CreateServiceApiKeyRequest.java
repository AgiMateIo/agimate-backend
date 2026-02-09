package ru.agimate.userapi.controller.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateServiceApiKeyRequest(
        @NotBlank String name,
        String description
) {}
