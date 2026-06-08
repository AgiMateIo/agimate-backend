package ru.agimate.controlapi.grpc.auth;

import ru.agimate.controlapi.util.GeneratedAppKey;

import java.util.regex.Pattern;

/**
 * Parsed authkey stored in worker-pools config.
 * <p>
 * Authkey format (single string, 80 chars): {prefix(4)}{keyId(12)}{keyHashHex(64)}.
 * The keyHash is SHA-256 of the secret embedded in the worker's full key —
 * the secret itself never appears in the config.
 */
public record ParsedWorkerAuthkey(String prefix, String keyId, String keyHash) {

    public static final int PREFIX_LENGTH = 4;
    public static final int KEYID_LENGTH = 12;
    public static final int KEYHASH_LENGTH = 64;
    public static final int TOTAL_LENGTH = PREFIX_LENGTH + KEYID_LENGTH + KEYHASH_LENGTH;

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z]{4}$");
    private static final Pattern KEYID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{12}$");
    private static final Pattern KEYHASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public static ParsedWorkerAuthkey parse(String authkey) {
        if (authkey == null || authkey.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid worker authkey length: expected " + TOTAL_LENGTH + " characters");
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
        return new ParsedWorkerAuthkey(prefix, keyId, keyHash);
    }

    public static String build(String prefix, GeneratedAppKey generated) {
        if (!generated.fullKey().startsWith(prefix)) {
            throw new IllegalArgumentException("Generated key prefix does not match");
        }
        return prefix + generated.keyId() + generated.secretHash();
    }
}
