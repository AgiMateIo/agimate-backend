package ru.agimate.connectorsapi.controller.dto.request;

public record UpdateConnectorsApiKeyRequest(
        String name,
        String description,
        Boolean enabled
) {}
