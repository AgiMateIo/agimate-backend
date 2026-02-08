package ru.agimate.connectorsapi.service.dto;

import ru.agimate.connectorsapi.database.entities.ServiceApiKey;

/**
 * Result of creating a new service API key.
 * Contains the entity and the full API key (only shown once).
 */
public record ServiceApiKeyCreateResult(
        ServiceApiKey serviceApiKey,
        String fullKey
) {}
