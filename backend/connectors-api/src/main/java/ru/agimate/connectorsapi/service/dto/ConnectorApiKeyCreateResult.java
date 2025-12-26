package ru.agimate.connectorsapi.service.dto;

import ru.agimate.connectorsapi.database.entities.ConnectorsApiKey;

/**
 * Result of creating a new connector API key.
 * Contains the entity and the full API key (only shown once).
 */
public record ConnectorApiKeyCreateResult(
        ConnectorsApiKey connectorsApiKey,
        String fullKey
) {}
