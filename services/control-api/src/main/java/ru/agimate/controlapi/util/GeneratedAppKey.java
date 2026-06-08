package ru.agimate.controlapi.util;

/**
 * Result of app key generation.
 *
 * @param fullKey    Complete key to show user once
 * @param keyId      12-character identifier for DB storage and lookup
 * @param secretHash SHA256 hex hash of secret for DB storage
 */
public record GeneratedAppKey(
        String fullKey,
        String keyId,
        String secretHash
) {}
