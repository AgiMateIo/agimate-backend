package ru.agimate.connectorsapi.controller.api.dto;

import java.util.UUID;

public record CredentialShortInfoResponse(
        UUID id,
        String name,
        String description,
        String connectorCode
) {
}
