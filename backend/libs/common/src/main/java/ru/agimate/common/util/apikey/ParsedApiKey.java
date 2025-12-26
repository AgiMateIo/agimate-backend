package ru.agimate.common.util.apikey;

/**
 * Parsed API key components.
 *
 * @param prefix   Service prefix ("agm")
 * @param type     Key type ("mob", "api", etc.)
 * @param keyId    12-character base64url identifier for DB lookup
 * @param secret   32 bytes of secret data
 * @param checksum 4 bytes CRC32 checksum
 */
public record ParsedApiKey(
        String prefix,
        String type,
        String keyId,
        byte[] secret,
        byte[] checksum
) {}
