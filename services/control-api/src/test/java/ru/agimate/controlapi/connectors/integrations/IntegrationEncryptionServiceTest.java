package ru.agimate.controlapi.connectors.integrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationEncryptionServiceTest {

    private IntegrationEncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        // Generate a 256-bit key (32 bytes) encoded as Base64
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) i;
        }
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);
        encryptionService = new IntegrationEncryptionService(base64Key);
    }

    @Test
    @DisplayName("encryptCredentials and decryptCredentials round-trip")
    void encryptDecryptCredentials_roundTrip() {
        Map<String, String> credentials = Map.of(
                "token", "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        );

        String encrypted = encryptionService.encryptCredentials(credentials);
        assertNotNull(encrypted);

        Map<String, String> decrypted = encryptionService.decryptCredentials(encrypted);
        assertEquals(credentials, decrypted);
    }

    @Test
    @DisplayName("different encryptions produce different ciphertexts (random IV)")
    void encrypt_differentIVs() {
        Map<String, String> credentials = Map.of("token", "test-token");

        String encrypted1 = encryptionService.encryptCredentials(credentials);
        String encrypted2 = encryptionService.encryptCredentials(credentials);

        assertNotEquals(encrypted1, encrypted2);

        // Both should decrypt to the same value
        assertEquals(credentials, encryptionService.decryptCredentials(encrypted1));
        assertEquals(credentials, encryptionService.decryptCredentials(encrypted2));
    }

    @Test
    @DisplayName("encryptCredentials handles multi-field credentials")
    void encryptDecryptCredentials_multiField() {
        Map<String, String> credentials = Map.of(
                "clientId", "my-client-id",
                "apiKey", "my-api-key-12345"
        );

        String encrypted = encryptionService.encryptCredentials(credentials);
        Map<String, String> decrypted = encryptionService.decryptCredentials(encrypted);

        assertEquals("my-client-id", decrypted.get("clientId"));
        assertEquals("my-api-key-12345", decrypted.get("apiKey"));
    }

    @Test
    @DisplayName("decrypt with tampered data throws exception")
    void decrypt_tamperedData_throws() {
        Map<String, String> credentials = Map.of("token", "test-token");
        String encrypted = encryptionService.encryptCredentials(credentials);

        // Tamper with the encrypted data
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "XX";

        assertThrows(IllegalStateException.class,
                () -> encryptionService.decryptCredentials(tampered));
    }
}
