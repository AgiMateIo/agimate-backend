package ru.agimate.userapi.service.dto;

import ru.agimate.userapi.database.entities.ServiceApiKey;

/**
 * Result of creating a new service API key.
 * Contains the entity and the full API key (only shown once).
 */
public record ServiceApiKeyCreateResult(
        ServiceApiKey serviceApiKey,
        String fullKey
) {}
