package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The fixture keys under {@code src/test/resources/ssh/} are throwaway test keys generated for this
 * test only. {@link #PASSPHRASE} is a fixed literal, not a secret, and protects nothing outside this
 * class. No real credentials are stored in the repository.
 */
@DisplayName("SshKeyLoader")
class SshKeyLoaderTest {

    private static final String PASSPHRASE = "agimate-test-passphrase";

    // Precomputed with `ssh-keygen -lf` from the fixture public keys, independent of MINA.
    private static final String ED25519_FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String RSA_FINGERPRINT = "SHA256:HWWZq7WaZg2J/vjQggH4YuEAUVFyEwmNzQYNmZXyzv8";

    private static final String PPK = """
            PuTTY-User-Key-File-2: ssh-rsa
            Encryption: none
            Comment: agimate test key
            Public-Lines: 1
            AAAAB3NzaC1yc2EAAAADAQABAAAAgQ==
            Private-Lines: 1
            AAAA
            Private-MAC: 0000000000000000000000000000000000000000
            """;

    private static String read(String resource) throws IOException {
        try (InputStream in = SshKeyLoaderTest.class.getResourceAsStream("/ssh/" + resource)) {
            if (in == null) {
                throw new IOException("Missing test fixture /ssh/" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String ed25519Key() {
        try {
            return read("id_ed25519_encrypted");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rsaKey() {
        try {
            return read("id_rsa_encrypted.pem");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fingerprint(KeyPair keyPair) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.getPublic());
    }

    @Nested
    @DisplayName("поддерживаемые форматы")
    class SupportedFormats {

        @Test
        @DisplayName("читает зашифрованный ключ OpenSSH Ed25519")
        void encryptedOpenSshEd25519() {
            KeyPair keyPair = SshKeyLoader.loadKeyPair(ed25519Key(), PASSPHRASE);

            assertNotNull(keyPair.getPrivate());
            assertNotNull(keyPair.getPublic());
            assertEquals(ED25519_FINGERPRINT, fingerprint(keyPair));
        }

        @Test
        @DisplayName("читает зашифрованный ключ PKCS#8 RSA PEM")
        void encryptedPkcs8Rsa() {
            KeyPair keyPair = SshKeyLoader.loadKeyPair(rsaKey(), PASSPHRASE);

            assertNotNull(keyPair.getPrivate());
            assertNotNull(keyPair.getPublic());
            assertEquals(RSA_FINGERPRINT, fingerprint(keyPair));
        }
    }

    @Nested
    @DisplayName("отклонения")
    class Rejections {

        @Test
        @DisplayName("отклоняет неверную парольную фразу")
        void wrongPassphrase() {
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(ed25519Key(), "wrong-passphrase"));
        }

        @Test
        @DisplayName("отклоняет отсутствие парольной фразы для зашифрованного ключа")
        void missingPassphrase() {
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(ed25519Key(), null));
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(ed25519Key(), "  "));
        }

        @Test
        @DisplayName("отклоняет ключ PuTTY PPK")
        void puttyPpk() {
            assertThrows(ConnectorException.class, () -> SshKeyLoader.loadKeyPair(PPK, null));
        }

        @Test
        @DisplayName("отклоняет мусорное содержимое")
        void garbage() {
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair("not a private key\n", null));
        }

        @Test
        @DisplayName("отклоняет путь вместо содержимого ключа")
        void pathInsteadOfContent() {
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair("/tmp/id_ed25519", null));
        }

        @Test
        @DisplayName("отклоняет пустой приватный ключ")
        void blankKey() {
            assertThrows(ConnectorException.class, () -> SshKeyLoader.loadKeyPair("", null));
            assertThrows(ConnectorException.class, () -> SshKeyLoader.loadKeyPair("   ", null));
        }

        @Test
        @DisplayName("отклоняет набор из нескольких ключей")
        void multipleKeys() {
            assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(ed25519Key() + "\n" + rsaKey(), PASSPHRASE));
        }
    }

    @Nested
    @DisplayName("безопасность секретов")
    class SecretSafety {

        @Test
        @DisplayName("сообщение об ошибке не содержит ключ или парольную фразу")
        void messageIsSafe() {
            ConnectorException exception = assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(ed25519Key(), "wrong-passphrase"));

            assertFalse(exception.getMessage().contains("wrong-passphrase"));
            assertFalse(exception.getMessage().contains(PASSPHRASE));
            assertFalse(exception.getMessage().contains("BEGIN"));
            assertFalse(exception.getMessage().contains("PRIVATE KEY"));
        }

        @Test
        @DisplayName("исключение не несёт библиотечную причину")
        void noLibraryCause() {
            ConnectorException exception = assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(rsaKey(), "wrong-passphrase"));

            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("toString исключения не содержит материал ключа")
        void toStringIsSafe() {
            ConnectorException exception = assertThrows(ConnectorException.class,
                    () -> SshKeyLoader.loadKeyPair(PPK, null));

            assertFalse(exception.toString().contains("AAAA"));
            assertFalse(exception.toString().contains("Private-Lines"));
        }
    }
}
