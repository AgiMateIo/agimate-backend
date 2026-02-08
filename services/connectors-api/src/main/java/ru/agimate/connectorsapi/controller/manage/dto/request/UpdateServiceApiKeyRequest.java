package ru.agimate.connectorsapi.controller.manage.dto.request;

public record UpdateServiceApiKeyRequest(
        String name,
        String description,
        Boolean enabled
) {}
