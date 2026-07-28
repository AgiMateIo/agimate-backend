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

    /**
     * Generate a random AES-256 encryption key.
     *
     * @return SecretKey for AES-256 encryption
     * @throws IllegalStateException if key generation fails
     */
    public static SecretKey generateAES256Key() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(AES_KEY_SIZE, new SecureRandom());
            return keyGenerator.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES-256 key", e);
        }
    }

    /**
     * Create SecretKey from Base64-encoded key string.
     *
     * @param base64Key Base64-encoded key (must be 32 bytes for AES-256)
     * @return SecretKey for AES-256 encryption
     * @throws IllegalArgumentException if key length is invalid
     */
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
     * Encrypt data using AES-256-GCM.
     *
     * Format of returned data: [IV (12 bytes)][Ciphertext (variable)][Authentication Tag (16 bytes)]
     *
     * @param data data to encrypt
     * @param key  AES-256 encryption key
     * @return encrypted data (IV + ciphertext + tag)
     * @throws IllegalStateException if encryption fails
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
     * Decrypt data using AES-256-GCM.
     *
     * Input format: [IV (12 bytes)][Ciphertext (variable)][Authentication Tag (16 bytes)]
     *
     * @param encrypted encrypted data (IV + ciphertext + tag)
     * @param key       AES-256 decryption key
     * @return decrypted data
     * @throws IllegalStateException if decryption fails (including authentication failure)
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
     * @param data plaintext
     * @param key  AES-256 key
     * @param iv   12-byte nonce
     * @param aad  additional authenticated data (may be {@code null}); not encrypted but authenticated
     * @return ciphertext (includes GCM tag), WITHOUT the IV
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
     * @param ciphertext ciphertext including GCM tag (as produced by {@link #encryptGcm})
     * @param key        AES-256 key
     * @param iv         12-byte nonce used at encryption
     * @param aad        same AAD as used at encryption (may be {@code null})
     * @return plaintext
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

    /**
     * Encrypt data and encode to Base64 URL-safe string.
     *
     * @param data data to encrypt
     * @param key  AES-256 encryption key
     * @return Base64 URL-safe encoded encrypted data
     */
    public static String encryptToBase64(byte[] data, SecretKey key) {
        byte[] encrypted = encryptAES256GCM(data, key);
        return Base64.getUrlEncoder().encodeToString(encrypted);
    }

    /**
     * Decode Base64 URL-safe string and decrypt data.
     *
     * @param base64Data Base64 URL-safe encoded encrypted data
     * @param key        AES-256 decryption key
     * @return decrypted data
     */
    public static byte[] decryptFromBase64(String base64Data, SecretKey key) {
        byte[] encrypted = Base64.getUrlDecoder().decode(base64Data);
        return decryptAES256GCM(encrypted, key);
    }
}
