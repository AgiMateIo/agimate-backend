package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture keys under {@code src/test/resources/ssh/} are throwaway test keys. Their passphrase is a
 * fixed literal in {@link SshKeyLoaderTest}, not a secret and not used anywhere outside these tests.
 */
@DisplayName("SshHostKeyVerifier")
class SshHostKeyVerifierTest {

    // Precomputed with `ssh-keygen -lf` from the fixture public keys, independent of MINA.
    private static final String ED25519_FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String RSA_FINGERPRINT = "SHA256:HWWZq7WaZg2J/vjQggH4YuEAUVFyEwmNzQYNmZXyzv8";

    private static PublicKey ed25519Key() throws Exception {
        return publicKey("id_ed25519_encrypted.pub");
    }

    private static PublicKey rsaKey() throws Exception {
        return publicKey("id_rsa_encrypted.pub");
    }

    private static PublicKey publicKey(String resource) throws Exception {
        String text = read(resource);
        List<AuthorizedKeyEntry> entries = AuthorizedKeyEntry.readAuthorizedKeys(new StringReader(text), false);
        return entries.getFirst().resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SshHostKeyVerifierTest.class.getResourceAsStream("/ssh/" + resource)) {
            if (in == null) {
                throw new IOException("Missing test fixture /ssh/" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("отпечаток")
    class Fingerprint {

        @Test
        @DisplayName("совпадает с fingerprint из ssh-keygen для обоих типов ключей")
        void matchesSshKeygen() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(ED25519_FINGERPRINT);

            assertEquals(ED25519_FINGERPRINT, verifier.fingerprint(ed25519Key()));
            assertEquals(RSA_FINGERPRINT, verifier.fingerprint(rsaKey()));
        }

        @Test
        @DisplayName("использует форму SHA-256 без padding base64")
        void unpaddedSha256() throws Exception {
            String fingerprint = new SshHostKeyVerifier(ED25519_FINGERPRINT).fingerprint(ed25519Key());

            assertTrue(fingerprint.startsWith("SHA256:"));
            assertFalse(fingerprint.endsWith("="));
        }
    }

    @Nested
    @DisplayName("проверка")
    class Verification {

        @Test
        @DisplayName("принимает закреплённый ключ сервера и не сообщает о несовпадении")
        void acceptsPinnedKey() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(ED25519_FINGERPRINT);

            assertTrue(verifier.verifyServerKey(null, null, ed25519Key()));
            assertNull(verifier.lastMismatch());
        }

        @Test
        @DisplayName("отклоняет другой ключ сервера и сохраняет безопасное несовпадение expected/actual")
        void rejectsOtherKey() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(ED25519_FINGERPRINT);

            assertFalse(verifier.verifyServerKey(null, null, rsaKey()));

            String mismatch = verifier.lastMismatch();
            assertTrue(mismatch.contains(ED25519_FINGERPRINT));
            assertTrue(mismatch.contains(RSA_FINGERPRINT));
        }

        @Test
        @DisplayName("успешная проверка очищает прошлое несовпадение")
        void successClearsMismatch() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(ED25519_FINGERPRINT);

            assertFalse(verifier.verifyServerKey(null, null, rsaKey()));
            assertTrue(verifier.verifyServerKey(null, null, ed25519Key()));

            assertNull(verifier.lastMismatch());
        }

        @Test
        @DisplayName("до первой проверки несовпадения нет")
        void noMismatchBeforeVerification() {
            assertNull(new SshHostKeyVerifier(ED25519_FINGERPRINT).lastMismatch());
        }

        @Test
        @DisplayName("текст несовпадения не содержит материал ключа")
        void mismatchHasNoKeyMaterial() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(ED25519_FINGERPRINT);

            verifier.verifyServerKey(null, null, rsaKey());

            String mismatch = verifier.lastMismatch();
            assertFalse(mismatch.contains("AAAA"));
            assertFalse(mismatch.contains("ssh-rsa"));
            assertFalse(mismatch.contains("ssh-ed25519"));
        }
    }

    @Nested
    @DisplayName("валидация fingerprint")
    class FingerprintValidation {

        @Test
        @DisplayName("отклоняет некорректный fingerprint до сетевого вызова")
        void malformedRejected() {
            String[] malformed = {
                    null,
                    "",
                    "   ",
                    "SHA256:",
                    "SHA256:short",
                    "MD5:aa:bb:cc",
                    "sha256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M",
                    ED25519_FINGERPRINT + "=",
                    "XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M"
            };

            for (String fingerprint : malformed) {
                assertThrows(ConnectorException.class,
                        () -> new SshHostKeyVerifier(fingerprint), String.valueOf(fingerprint));
            }
        }

        @Test
        @DisplayName("принимает fingerprint с пробелами по краям")
        void trimsWhitespace() throws Exception {
            SshHostKeyVerifier verifier = new SshHostKeyVerifier(" " + ED25519_FINGERPRINT + "\n");

            assertTrue(verifier.verifyServerKey(null, null, ed25519Key()));
        }
    }
}
