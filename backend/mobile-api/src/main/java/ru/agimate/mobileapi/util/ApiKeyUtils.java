package ru.agimate.mobileapi.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Utility class for API key generation and validation.
 * <p>
 * Key format: {prefix}{type}{keyid}{payload} (no separators, positional)
 * <ul>
 *   <li>prefix: "a" (1 char, Agimate)</li>
 *   <li>type: key type, exactly 3 lowercase letters (e.g., "mob", "api", "dev")</li>
 *   <li>keyid: base64url(timestamp_4bytes || random_5bytes) = 12 chars</li>
 *   <li>payload: base64url(secret_32bytes || crc32_4bytes) = 48 chars</li>
 * </ul>
 * Total length: 64 characters (1 + 3 + 12 + 48)
 */
public final class ApiKeyUtils {

    public static final String PREFIX = "a";
    public static final String DEFAULT_TYPE = "mob";

    private static final int PREFIX_LENGTH = 1;
    private static final int TYPE_LENGTH = 3;

    private static final int TIMESTAMP_BYTES = 4;
    private static final int RANDOM_KEYID_BYTES = 5;
    private static final int KEYID_BYTES = TIMESTAMP_BYTES + RANDOM_KEYID_BYTES; // 9
    private static final int KEYID_LENGTH = 12; // base64url(9 bytes) = 12 chars

    private static final int SECRET_BYTES = 32;
    private static final int CHECKSUM_BYTES = 4;
    private static final int PAYLOAD_BYTES = SECRET_BYTES + CHECKSUM_BYTES; // 36
    private static final int PAYLOAD_LENGTH = 48; // base64url(36 bytes) = 48 chars

    private static final int TOTAL_LENGTH = PREFIX_LENGTH + TYPE_LENGTH + KEYID_LENGTH + PAYLOAD_LENGTH; // 64

    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z]{3}$");
    private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeyUtils() {
    }

    /**
     * Generate a new API key with the default type.
     *
     * @return generated key with fullKey, keyId, and secretHash
     */
    public static GeneratedApiKey generate() {
        return generate(DEFAULT_TYPE);
    }

    /**
     * Generate a new API key with the specified type.
     *
     * @param type key type (exactly 3 lowercase letters)
     * @return generated key with fullKey, keyId, and secretHash
     * @throws IllegalArgumentException if type format is invalid
     */
    public static GeneratedApiKey generate(String type) {
        if (type == null || !TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Type must be exactly 3 lowercase letters");
        }

        // 1. Generate keyId = timestamp(4) + random(5) → base64url → 12 chars
        byte[] randomKeyIdPart = new byte[RANDOM_KEYID_BYTES];
        SECURE_RANDOM.nextBytes(randomKeyIdPart);

        ByteBuffer keyIdBuffer = ByteBuffer.allocate(KEYID_BYTES);
        keyIdBuffer.putInt((int) Instant.now().getEpochSecond());
        keyIdBuffer.put(randomKeyIdPart);
        String keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(keyIdBuffer.array());

        // 2. Generate secret = random(32)
        byte[] secret = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(secret);

        // 3. Calculate checksum = crc32(prefix || type || keyid || secret)
        byte[] checksum = calculateChecksum(PREFIX, type, keyId, secret);

        // 4. Build payload = base64url(secret || checksum) → 48 chars
        ByteBuffer payloadBuffer = ByteBuffer.allocate(PAYLOAD_BYTES);
        payloadBuffer.put(secret);
        payloadBuffer.put(checksum);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBuffer.array());

        // 5. Build full key without separators (positional format)
        String fullKey = PREFIX + type + keyId + payload;

        // 6. Calculate secretHash = sha256(secret) hex
        String secretHash = hashSecret(secret);

        return new GeneratedApiKey(fullKey, keyId, secretHash);
    }

    /**
     * Parse an API key string into its components.
     * <p>
     * Uses simple positional parsing (no separators).
     * Format: {prefix}{type}{keyid}{payload} all parts have fixed lengths.
     *
     * @param apiKey the full API key string
     * @return parsed key components
     * @throws IllegalArgumentException if the key format is invalid
     */
    public static ParsedApiKey parse(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or empty");
        }

        if (apiKey.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException("Invalid API key length: expected " + TOTAL_LENGTH + " characters");
        }

        // Simple positional parsing (all parts have fixed lengths)
        int pos = 0;

        // prefix: 1 character
        String prefix = apiKey.substring(pos, pos + PREFIX_LENGTH);
        pos += PREFIX_LENGTH;

        // type: 3 characters
        String type = apiKey.substring(pos, pos + TYPE_LENGTH);
        pos += TYPE_LENGTH;

        // keyId: 12 characters
        String keyId = apiKey.substring(pos, pos + KEYID_LENGTH);
        pos += KEYID_LENGTH;

        // payload: 48 characters
        String payload = apiKey.substring(pos);

        // Validate prefix
        if (!PREFIX.equals(prefix)) {
            throw new IllegalArgumentException("Invalid API key prefix: expected '" + PREFIX + "'");
        }

        // Validate type
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid type: must be exactly 3 lowercase letters");
        }

        // Validate keyId format
        if (!BASE64URL_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid keyId: must be base64url encoded");
        }

        // Validate payload format
        if (!BASE64URL_PATTERN.matcher(payload).matches()) {
            throw new IllegalArgumentException("Invalid payload: must be base64url encoded");
        }

        // Decode payload
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

        return new ParsedApiKey(prefix, type, keyId, secret, checksum);
    }

    /**
     * Verify the CRC32 checksum of a parsed API key.
     *
     * @param parsed the parsed API key
     * @return true if checksum is valid
     */
    public static boolean verifyChecksum(ParsedApiKey parsed) {
        byte[] expected = calculateChecksum(parsed.prefix(), parsed.type(), parsed.keyId(), parsed.secret());
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
        return hashSecret(secret).equalsIgnoreCase(storedHash);
    }

    /**
     * Calculate SHA256 hash of secret and return as hex string.
     *
     * @param secret the secret bytes
     * @return lowercase hex string of SHA256 hash
     */
    public static String hashSecret(byte[] secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Calculate CRC32 checksum of key components.
     */
    private static byte[] calculateChecksum(String prefix, String type, String keyId, byte[] secret) {
        CRC32 crc = new CRC32();
        crc.update(prefix.getBytes(StandardCharsets.UTF_8));
        crc.update(type.getBytes(StandardCharsets.UTF_8));
        crc.update(keyId.getBytes(StandardCharsets.UTF_8));
        crc.update(secret);
        return ByteBuffer.allocate(CHECKSUM_BYTES).putInt((int) crc.getValue()).array();
    }
}
