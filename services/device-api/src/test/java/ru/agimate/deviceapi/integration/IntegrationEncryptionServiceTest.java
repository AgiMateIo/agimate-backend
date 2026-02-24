package ru.agimate.deviceapi.integration;

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
    @DisplayName("encrypt and decrypt round-trip")
    void encryptDecrypt_roundTrip() {
        String plaintext = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";

        var result = encryptionService.encrypt(plaintext);
        assertNotNull(result.encryptedData());
        assertNotNull(result.iv());
        assertNotEquals(plaintext, result.encryptedData());

        String decrypted = encryptionService.decrypt(result.encryptedData(), result.iv());
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("different encryptions produce different ciphertexts (random IV)")
    void encrypt_differentIVs() {
        String plaintext = "test-token";

        var result1 = encryptionService.encrypt(plaintext);
        var result2 = encryptionService.encrypt(plaintext);

        assertNotEquals(result1.encryptedData(), result2.encryptedData());
        assertNotEquals(result1.iv(), result2.iv());

        // Both should decrypt to the same value
        assertEquals(plaintext, encryptionService.decrypt(result1.encryptedData(), result1.iv()));
        assertEquals(plaintext, encryptionService.decrypt(result2.encryptedData(), result2.iv()));
    }

    @Test
    @DisplayName("decrypt with wrong IV throws exception")
    void decrypt_wrongIV_throws() {
        String plaintext = "test-token";
        var result = encryptionService.encrypt(plaintext);

        // Use a different IV
        byte[] wrongIv = new byte[12];
        String wrongIvBase64 = Base64.getEncoder().encodeToString(wrongIv);

        assertThrows(IllegalStateException.class,
                () -> encryptionService.decrypt(result.encryptedData(), wrongIvBase64));
    }

    @Test
    @DisplayName("encryptCredentials and decryptCredentials round-trip for Map")
    void encryptDecryptCredentials_roundTrip() {
        Map<String, String> credentials = Map.of(
                "token", "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        );

        var result = encryptionService.encryptCredentials(credentials);
        assertNotNull(result.encryptedData());
        assertNotNull(result.iv());

        Map<String, String> decrypted = encryptionService.decryptCredentials(result.encryptedData(), result.iv());
        assertEquals(credentials, decrypted);
    }

    @Test
    @DisplayName("encryptCredentials handles multi-field credentials")
    void encryptDecryptCredentials_multiField() {
        Map<String, String> credentials = Map.of(
                "clientId", "my-client-id",
                "apiKey", "my-api-key-12345"
        );

        var result = encryptionService.encryptCredentials(credentials);
        Map<String, String> decrypted = encryptionService.decryptCredentials(result.encryptedData(), result.iv());

        assertEquals("my-client-id", decrypted.get("clientId"));
        assertEquals("my-api-key-12345", decrypted.get("apiKey"));
    }
}
