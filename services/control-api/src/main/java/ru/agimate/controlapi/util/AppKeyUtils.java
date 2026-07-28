package ru.agimate.controlapi.util;

import ru.agimate.common.util.CryptoUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Utility class for app key generation and validation.
 * <p>
 * Key format: {prefix}{keyid}{payload} (no separators, positional)
 * <ul>
 *   <li>prefix: exactly 4 lowercase letters identifying key type (e.g., "agnt")</li>
 *   <li>keyid: base64url(timestamp_4bytes || random_5bytes) = 12 chars</li>
 *   <li>payload: base64url(secret_32bytes || crc32_4bytes) = 48 chars</li>
 * </ul>
 * Total length: 64 characters (4 + 12 + 48)
 * <p>
 * The keyid is what the lookup is done by, which is why it must be independently readable without
 * decoding the payload — hence fixed positions rather than separators. The CRC32 rejects typos
 * before the database is touched; it is not authentication, that is {@link #verifySecret}.
 * <p>
 * Which prefix means what, and where each key is stored: {@code docs/contracts/api-keys.md}.
 */
public final class AppKeyUtils {

    private static final int PREFIX_LENGTH = 4;

    private static final int TIMESTAMP_BYTES = 4;
    private static final int RANDOM_KEYID_BYTES = 5;
    private static final int KEYID_BYTES = TIMESTAMP_BYTES + RANDOM_KEYID_BYTES; // 9
    private static final int KEYID_LENGTH = 12; // base64url(9 bytes) = 12 chars

    private static final int SECRET_BYTES = 32;
    private static final int CHECKSUM_BYTES = 4;
    private static final int PAYLOAD_BYTES = SECRET_BYTES + CHECKSUM_BYTES; // 36
    private static final int PAYLOAD_LENGTH = 48; // base64url(36 bytes) = 48 chars

    private static final int TOTAL_LENGTH = PREFIX_LENGTH + KEYID_LENGTH + PAYLOAD_LENGTH; // 64

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z]{4}$");
    private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AppKeyUtils() {
    }

    /**
     * Generate a new app key with the specified prefix.
     *
     * @param prefix key prefix (exactly 4 lowercase letters, e.g. "agnt")
     * @return generated key with fullKey, keyId, and secretHash
     * @throws IllegalArgumentException if prefix format is invalid
     */
    public static GeneratedAppKey generate(String prefix) {
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Prefix must be exactly 4 lowercase letters");
        }

        byte[] randomKeyIdPart = new byte[RANDOM_KEYID_BYTES];
        SECURE_RANDOM.nextBytes(randomKeyIdPart);

        ByteBuffer keyIdBuffer = ByteBuffer.allocate(KEYID_BYTES);
        keyIdBuffer.putInt((int) Instant.now().getEpochSecond());
        keyIdBuffer.put(randomKeyIdPart);
        String keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(keyIdBuffer.array());

        byte[] secret = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(secret);

        byte[] checksum = calculateChecksum(prefix, keyId, secret);

        ByteBuffer payloadBuffer = ByteBuffer.allocate(PAYLOAD_BYTES);
        payloadBuffer.put(secret);
        payloadBuffer.put(checksum);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBuffer.array());

        String fullKey = prefix + keyId + payload;

        // Only the hash is returned for storage — the secret itself is never persisted.
        String secretHash = hashSecret(secret);

        return new GeneratedAppKey(fullKey, keyId, secretHash);
    }

    /**
     * Parse an app key string into its components.
     * <p>
     * Uses simple positional parsing (no separators).
     * Format: {prefix}{keyid}{payload} all parts have fixed lengths.
     *
     * @param key the full app key string
     * @return parsed key components
     * @throws IllegalArgumentException if the key format is invalid
     */
    public static ParsedAppKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or empty");
        }

        if (key.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException("Invalid API key length: expected " + TOTAL_LENGTH + " characters");
        }

        int pos = 0;

        String prefix = key.substring(pos, pos + PREFIX_LENGTH);
        pos += PREFIX_LENGTH;

        String keyId = key.substring(pos, pos + KEYID_LENGTH);
        pos += KEYID_LENGTH;

        String payload = key.substring(pos);

        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid prefix: must be exactly 4 lowercase letters");
        }

        if (!BASE64URL_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid keyId: must be base64url encoded");
        }

        if (!BASE64URL_PATTERN.matcher(payload).matches()) {
            throw new IllegalArgumentException("Invalid payload: must be base64url encoded");
        }

        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payload: failed to decode base64url", e);
        }

        if (payloadBytes.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid payload: decoded to " + payloadBytes.length + " bytes, expected " + PAYLOAD_BYTES);
        }

        byte[] secret = Arrays.copyOfRange(payloadBytes, 0, SECRET_BYTES);
        byte[] checksum = Arrays.copyOfRange(payloadBytes, SECRET_BYTES, PAYLOAD_BYTES);

        return new ParsedAppKey(prefix, keyId, secret, checksum);
    }

    /**
     * Verify the CRC32 checksum of a parsed app key.
     *
     * @param parsed the parsed app key
     * @return true if checksum is valid
     */
    public static boolean verifyChecksum(ParsedAppKey parsed) {
        byte[] expected = calculateChecksum(parsed.prefix(), parsed.keyId(), parsed.secret());
        return Arrays.equals(expected, parsed.checksum());
    }

    /**
     * Verify the secret against a stored SHA256 hash.
     *
     * @param secret     the secret bytes from parsed key
     * @param storedHash the SHA256 hex hash from database
     * @return true if secret matches the hash
     */
    public static boolean verifySecret(byte[] secret, String storedHash) {
        if (secret == null || storedHash == null) {
            return false;
        }
        // Constant-time: сравнение хэшей не должно течь по таймингу.
        return MessageDigest.isEqual(
                hashSecret(secret).getBytes(StandardCharsets.UTF_8),
                storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Calculate SHA256 hash of secret and return as hex string.
     *
     * @param secret the secret bytes
     * @return lowercase hex string of SHA256 hash
     */
    public static String hashSecret(byte[] secret) {
        return CryptoUtils.sha256Hex(secret);
    }

    /**
     * Calculate CRC32 checksum of key components.
     */
    private static byte[] calculateChecksum(String prefix, String keyId, byte[] secret) {
        CRC32 crc = new CRC32();
        crc.update(prefix.getBytes(StandardCharsets.UTF_8));
        crc.update(keyId.getBytes(StandardCharsets.UTF_8));
        crc.update(secret);
        return ByteBuffer.allocate(CHECKSUM_BYTES).putInt((int) crc.getValue()).array();
    }
}
