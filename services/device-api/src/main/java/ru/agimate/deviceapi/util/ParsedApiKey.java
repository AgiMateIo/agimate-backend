package ru.agimate.deviceapi.util;

/**
 * Parsed API key components.
 *
 * @param prefix   4-character key type prefix (e.g., "amob", "acon", "adev")
 * @param keyId    12-character base64url identifier for DB lookup
 * @param secret   32 bytes of secret data
 * @param checksum 4 bytes CRC32 checksum
 */
public record ParsedApiKey(
        String prefix,
        String keyId,
        byte[] secret,
        byte[] checksum
) {}
