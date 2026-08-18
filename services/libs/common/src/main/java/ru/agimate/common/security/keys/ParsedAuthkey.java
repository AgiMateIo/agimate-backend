package ru.agimate.common.security.keys;

import java.util.regex.Pattern;

/**
 * An authkey as it is stored in configuration: {@code {prefix(4)}{keyId(12)}{keyHashHex(64)}}, 80
 * chars in one string. The keyHash is SHA-256 of the secret inside the 64-char full key its holder
 * presents, so the secret itself never appears in a config file or a deployment manifest.
 *
 * <p>Used by everything whose keys live outside the database — worker pools ({@code wrkp}) and the
 * internal service calls of user-api ({@code intr}); the keys of agents and apps are rows, and are
 * verified against their own columns instead.
 */
public record ParsedAuthkey(String prefix, String keyId, String keyHash) {

    public static final int PREFIX_LENGTH = 4;
    public static final int KEYID_LENGTH = 12;
    public static final int KEYHASH_LENGTH = 64;
    public static final int TOTAL_LENGTH = PREFIX_LENGTH + KEYID_LENGTH + KEYHASH_LENGTH;

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z]{4}$");
    private static final Pattern KEYID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{12}$");
    private static final Pattern KEYHASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public static ParsedAuthkey parse(String authkey) {
        if (authkey == null || authkey.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid authkey length: expected " + TOTAL_LENGTH + " characters");
        }
        String prefix = authkey.substring(0, PREFIX_LENGTH);
        String keyId = authkey.substring(PREFIX_LENGTH, PREFIX_LENGTH + KEYID_LENGTH);
        String keyHash = authkey.substring(PREFIX_LENGTH + KEYID_LENGTH);

        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid authkey prefix: must be 4 lowercase letters");
        }
        if (!KEYID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid authkey keyId: must be 12-char base64url");
        }
        if (!KEYHASH_PATTERN.matcher(keyHash).matches()) {
            throw new IllegalArgumentException("Invalid authkey keyHash: must be 64-char hex SHA-256");
        }
        return new ParsedAuthkey(prefix, keyId, keyHash);
    }

    public static String build(String prefix, GeneratedAppKey generated) {
        if (!generated.fullKey().startsWith(prefix)) {
            throw new IllegalArgumentException("Generated key prefix does not match");
        }
        return prefix + generated.keyId() + generated.secretHash();
    }
}
