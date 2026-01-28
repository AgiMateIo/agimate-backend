package ru.agimate.connectorsapi.controller.manage.dto.request;

public record UpdateConnectorsApiKeyRequest(
        String name,
        String description,
        Boolean enabled
) {}
