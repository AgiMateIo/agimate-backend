package ru.agimate.common.util.apikey;

/**
 * Result of API key generation.
 *
 * @param fullKey    Complete key to show user once (agm_type_keyid_payload)
 * @param keyId      12-character identifier for DB storage and lookup
 * @param secretHash SHA256 hex hash of secret for DB storage
 */
public record GeneratedApiKey(
        String fullKey,
        String keyId,
        String secretHash
) {}
