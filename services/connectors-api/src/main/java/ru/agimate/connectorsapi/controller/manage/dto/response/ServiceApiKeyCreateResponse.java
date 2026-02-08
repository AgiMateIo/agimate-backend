package ru.agimate.connectorsapi.controller.manage.dto.response;

public record ServiceApiKeyCreateResponse(
        ServiceApiKeyResponse apiKey,
        String fullKey  // Shown only once!
) {}
