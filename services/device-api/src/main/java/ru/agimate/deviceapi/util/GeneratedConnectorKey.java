package ru.agimate.deviceapi.util;

/**
 * Result of connector key generation.
 *
 * @param fullKey    Complete key to show user once
 * @param keyId      12-character identifier for DB storage and lookup
 * @param secretHash SHA256 hex hash of secret for DB storage
 */
public record GeneratedConnectorKey(
        String fullKey,
        String keyId,
        String secretHash
) {}
