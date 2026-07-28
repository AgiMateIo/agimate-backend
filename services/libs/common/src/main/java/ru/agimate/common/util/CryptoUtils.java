package ru.agimate.common.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Utility class for cryptographic operations using AES-256-GCM encryption.
 *
 * Provides methods for:
 * - AES-256 key generation
 * - AES-256-GCM encryption/decryption
 * - Base64 encoding/decoding of encrypted data
 *
 * Uses Galois/Counter Mode (GCM) for authenticated encryption with associated data (AEAD).
 * This provides both confidentiality and authenticity guarantees.
 */
@Slf4j
@UtilityClass
public class CryptoUtils {

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits (recommended for GCM)
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    private static final int AES_KEY_SIZE = 256; // AES-256

    /** Generate a random AES-256 encryption key. */
    public static SecretKey generateAES256Key() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(AES_KEY_SIZE, new SecureRandom());
            return keyGenerator.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES-256 key", e);
        }
    }

    /** Decoded key material must be exactly 32 bytes; anything else throws rather than being padded. */
    public static SecretKey keyFromBase64(String base64Key) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(base64Key);

            if (decodedKey.length != 32) {
                throw new IllegalArgumentException(
                    String.format("AES-256 key must be 32 bytes, got %d bytes. Generate with: openssl rand -base64 32",
                                decodedKey.length)
                );
            }

            return new SecretKeySpec(decodedKey, "AES");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode Base64 key", e);
        }
    }

    /**
     * AES-256-GCM with a generated IV prepended to the output:
     * {@code [IV (12)][ciphertext][GCM tag (16)]}. Self-contained, so the caller stores one blob —
     * unlike {@link #encryptGcm}, where the IV is the caller's problem.
     */
    public static byte[] encryptAES256GCM(byte[] data, SecretKey key) {
        try {
            // A fresh IV per call is what makes GCM safe; reusing one across two messages under the
            // same key breaks confidentiality outright. It is not secret, so it travels in the clear
            // alongside the ciphertext — that is why the return value is a single blob.
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            byte[] ciphertext = cipher.doFinal(data); // GCM appends the auth tag itself

            byte[] encrypted = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, encrypted, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, encrypted, GCM_IV_LENGTH, ciphertext.length);

            return encrypted;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt data with AES-256-GCM", e);
        }
    }

    /**
     * Inverse of {@link #encryptAES256GCM}. Throws on a tag mismatch — tampered data never comes
     * back as plaintext.
     */
    public static byte[] decryptAES256GCM(byte[] encrypted, SecretKey key) {
        try {
            // Anything shorter cannot hold an IV and a tag, so it was never produced by encrypt.
            if (encrypted.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("Encrypted data too short");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encrypted, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[encrypted.length - GCM_IV_LENGTH];
            System.arraycopy(encrypted, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            // Throws on a bad tag — tampering surfaces here, not as garbage plaintext.
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt data with AES-256-GCM (possibly tampered or wrong key)", e);
        }
    }

    /**
     * Generate {@code length} cryptographically random bytes (e.g. a GCM nonce/IV).
     */
    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Recommended GCM nonce length (12 bytes). */
    public static int gcmIvLength() {
        return GCM_IV_LENGTH;
    }

    /** Generate {@code numBytes} random bytes as a lowercase hex string (e.g. for opaque tokens). */
    public static String randomHex(int numBytes) {
        return HexFormat.of().formatHex(randomBytes(numBytes));
    }

    /** SHA-256 of {@code data} as a lowercase hex string (e.g. for secret fingerprints/lookups). */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** SHA-256 of the UTF-8 bytes of {@code value} as a lowercase hex string. */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * AES-256-GCM encryption with an explicit IV/nonce and optional AAD (additional authenticated
     * data). Unlike {@link #encryptAES256GCM} the IV is NOT prepended — caller stores it separately.
     * Used by envelope encryption (per-row DEK bound to its owner via AAD).
     *
     * @param iv  12-byte nonce; reusing one across two messages under the same key breaks GCM
     * @param aad additional authenticated data (may be {@code null}); not encrypted but authenticated
     */
    public static byte[] encryptGcm(byte[] data, SecretKey key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt (AES-256-GCM with AAD)", e);
        }
    }

    /**
     * AES-256-GCM decryption with an explicit IV/nonce and optional AAD. Throws if the tag or AAD
     * do not match (tampered data or wrong owner binding).
     *
     * @param iv  the same nonce used at encryption
     * @param aad the same AAD used at encryption (may be {@code null})
     */
    public static byte[] decryptGcm(byte[] ciphertext, SecretKey key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt (AES-256-GCM, tampered/wrong key or AAD)", e);
        }
    }

    /**
     * Build an AES-256 {@link SecretKey} from raw 32-byte key material.
     */
    public static SecretKey keyFromBytes(byte[] keyBytes) {
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes, got " + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** {@link #encryptAES256GCM} in Base64 URL-safe form — for cookies, headers and other text carriers. */
    public static String encryptToBase64(byte[] data, SecretKey key) {
        byte[] encrypted = encryptAES256GCM(data, key);
        return Base64.getUrlEncoder().encodeToString(encrypted);
    }

    /** Inverse of {@link #encryptToBase64}. */
    public static byte[] decryptFromBase64(String base64Data, SecretKey key) {
        byte[] encrypted = Base64.getUrlDecoder().decode(base64Data);
        return decryptAES256GCM(encrypted, key);
    }
}
