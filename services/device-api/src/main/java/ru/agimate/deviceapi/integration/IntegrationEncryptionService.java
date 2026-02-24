package ru.agimate.deviceapi.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.JsonUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

@Service
public class IntegrationEncryptionService {

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public IntegrationEncryptionService(@Value("${app.integration.encryption-key}") String encryptionKey) {
        this.secretKey = CryptoUtils.keyFromBase64(encryptionKey);
    }

    public String encryptCredentials(Map<String, String> credentials) {
        String json = JsonUtils.writeValueAsString(credentials);
        return CryptoUtils.encryptToBase64(json.getBytes(StandardCharsets.UTF_8), secretKey);
    }

    public Map<String, String> decryptCredentials(String encryptedData) {
        byte[] decrypted = CryptoUtils.decryptFromBase64(encryptedData, secretKey);
        return JsonUtils.readValue(new String(decrypted, StandardCharsets.UTF_8), JsonUtils.MAP_STRING_TYPE_REFERENCE);
    }

    public String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
