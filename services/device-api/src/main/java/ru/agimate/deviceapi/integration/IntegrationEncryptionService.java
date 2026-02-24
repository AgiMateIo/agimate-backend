package ru.agimate.deviceapi.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import ru.agimate.common.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
public class IntegrationEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public IntegrationEncryptionService(@Value("${app.integration.encryption-key}") String encryptionKey) {
        byte[] keyBytes;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("No integration encryption key configured, generating random key (not suitable for production)");
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
        } else {
            keyBytes = Base64.getDecoder().decode(encryptionKey);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public EncryptionResult encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return new EncryptionResult(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt token", e);
        }
    }

    public String decrypt(String encryptedToken, String ivBase64) {
        try {
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            byte[] encrypted = Base64.getDecoder().decode(encryptedToken);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt token", e);
        }
    }

    public EncryptionResult encryptCredentials(Map<String, String> credentials) {
        String json = JsonUtils.writeValueAsString(credentials);
        return encrypt(json);
    }

    public Map<String, String> decryptCredentials(String encryptedData, String iv) {
        String json = decrypt(encryptedData, iv);
        return JsonUtils.readValue(json, JsonUtils.MAP_STRING_TYPE_REFERENCE);
    }

    public String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record EncryptionResult(String encryptedData, String iv) {}
}
