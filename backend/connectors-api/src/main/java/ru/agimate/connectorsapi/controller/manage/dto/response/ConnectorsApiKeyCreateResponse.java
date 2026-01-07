package ru.agimate.connectorsapi.controller.manage.dto.response;

public record ConnectorsApiKeyCreateResponse(
        ConnectorsApiKeyResponse apiKey,
        String fullKey  // Shown only once!
) {}
