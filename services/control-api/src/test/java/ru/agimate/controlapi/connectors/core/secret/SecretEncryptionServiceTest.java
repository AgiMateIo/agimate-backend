package ru.agimate.controlapi.connectors.core.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.controlapi.database.entities.Secret;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SecretEncryptionService — envelope-шифрование с AAD-привязкой к владельцу")
class SecretEncryptionServiceTest {

    private static final String KEK = Base64.getEncoder()
            .encodeToString(CryptoUtils.generateAES256Key().getEncoded());

    private final SecretEncryptionService service = new SecretEncryptionService(KEK);

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("decrypt с тем же entity+ownerId возвращает исходный plaintext")
        void decryptsBackWithSameOwner() {
            String entity = "connection";
            UUID ownerId = UUID.randomUUID();
            byte[] plaintext = "{\"token\":\"secret-value\"}".getBytes(StandardCharsets.UTF_8);

            Secret secret = service.encrypt(entity, ownerId, plaintext);
            byte[] decrypted = service.decrypt(secret, ownerId);

            assertArrayEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("каждый секрет имеет свой DEK/IV — шифртексты одинакового plaintext различаются")
        void usesFreshDekPerSecret() {
            UUID ownerId = UUID.randomUUID();
            byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);

            Secret a = service.encrypt("connection", ownerId, plaintext);
            Secret b = service.encrypt("connection", ownerId, plaintext);

            assertNotEquals(a.getEncryptedData(), b.getEncryptedData());
            assertNotEquals(a.getEncryptedDek(), b.getEncryptedDek());
        }
    }

    @Nested
    @DisplayName("AAD-привязка")
    class AadBinding {

        @Test
        @DisplayName("decrypt с другим ownerId падает (строку нельзя перенести на другого владельца)")
        void rejectsForeignOwner() {
            UUID owner = UUID.randomUUID();
            UUID otherOwner = UUID.randomUUID();
            Secret secret = service.encrypt("connection", owner, "data".getBytes(StandardCharsets.UTF_8));

            assertThrows(RuntimeException.class, () -> service.decrypt(secret, otherOwner));
        }

        @Test
        @DisplayName("decrypt с другим entity падает")
        void rejectsForeignEntity() {
            UUID owner = UUID.randomUUID();
            Secret secret = service.encrypt("connection", owner, "data".getBytes(StandardCharsets.UTF_8));
            secret.setEntity("llm_provider");

            assertThrows(RuntimeException.class, () -> service.decrypt(secret, owner));
        }
    }
}
