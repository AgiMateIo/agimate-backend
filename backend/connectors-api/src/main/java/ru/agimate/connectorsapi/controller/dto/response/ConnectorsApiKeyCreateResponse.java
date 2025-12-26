package ru.agimate.connectorsapi.controller.dto.response;

public record ConnectorsApiKeyCreateResponse(
        ConnectorsApiKeyResponse apiKey,
        String fullKey  // Shown only once!
) {}
