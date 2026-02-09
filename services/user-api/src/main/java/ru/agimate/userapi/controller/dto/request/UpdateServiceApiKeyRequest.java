package ru.agimate.userapi.controller.dto.request;

public record UpdateServiceApiKeyRequest(
        String name,
        String description,
        Boolean enabled
) {}
