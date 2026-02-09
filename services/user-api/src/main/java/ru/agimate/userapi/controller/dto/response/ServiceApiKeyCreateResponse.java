package ru.agimate.userapi.controller.dto.response;

public record ServiceApiKeyCreateResponse(
        ServiceApiKeyResponse apiKey,
        String fullKey  // Shown only once!
) {}
