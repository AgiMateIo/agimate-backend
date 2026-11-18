package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SshConnectionConfig")
class SshConnectionConfigTest {

    private static final String FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nS3CRET-KEY-BODY\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String PASSPHRASE = "PaSSphrase-VALUE";
    private static final String PASSWORD = "PaSSword-VALUE";

    private static Map<String, String> credentials() {
        Map<String, String> values = new HashMap<>();
        values.put("host", "example.com");
        values.put("username", "root");
        values.put("hostKeyFingerprint", FINGERPRINT);
        values.put("password", PASSWORD);
        return values;
    }

    private static Map<String, String> keyCredentials() {
        Map<String, String> values = credentials();
        values.remove("password");
        values.put("privateKey", PRIVATE_KEY);
        return values;
    }

    /** A first connection: the platform has not learned the server key yet. */
    private static Map<String, String> learningCredentials() {
        Map<String, String> values = credentials();
        values.remove("hostKeyFingerprint");
        return values;
    }

    private static SshConnectionConfig.ValidationException rejected(Map<String, String> values) {
        return assertThrows(SshConnectionConfig.ValidationException.class,
                () -> SshConnectionConfig.fromCredentials(values));
    }

    @Nested
    @DisplayName("нормализация")
    class Normalization {

        @Test
        @DisplayName("обрезает и приводит хост к нижнему регистру, сохраняя регистр имени пользователя")
        void canonicalIdentifier() {
            Map<String, String> values = credentials();
            values.put("host", " Example.COM ");
            values.put("username", " Root ");

            SshConnectionConfig config = SshConnectionConfig.fromCredentials(values);

            assertEquals("example.com", config.host());
            assertEquals("Root", config.username());
            assertEquals("Root@example.com:22", config.identifier());
            assertEquals("SSH: Root@example.com:22", config.displayName());
        }

        @Test
        @DisplayName("пустой порт заменяется на 22")
        void blankPortDefaults() {
            Map<String, String> values = credentials();
            values.put("port", "   ");

            assertEquals(22, SshConnectionConfig.fromCredentials(values).port());
        }

        @Test
        @DisplayName("сохраняет явный порт")
        void explicitPort() {
            Map<String, String> values = credentials();
            values.put("port", "2222");

            assertEquals("root@example.com:2222", SshConnectionConfig.fromCredentials(values).identifier());
        }

        @Test
        @DisplayName("принимает границы порта 1 и 65535")
        void portBounds() {
            Map<String, String> values = credentials();

            values.put("port", "1");
            assertEquals(1, SshConnectionConfig.fromCredentials(values).port());

            values.put("port", "65535");
            assertEquals(65535, SshConnectionConfig.fromCredentials(values).port());
        }

        @Test
        @DisplayName("выводит канонические открытые параметры без секретов")
        void derivedCredentials() {
            SshConnectionConfig config = SshConnectionConfig.fromCredentials(credentials());

            assertEquals(Map.of("host", "example.com", "port", "22"), config.derivedCredentials());
        }

        @Test
        @DisplayName("сохраняет пароль и содержимое ключа без изменений")
        void preservesSecretsVerbatim() {
            Map<String, String> passwordValues = credentials();
            passwordValues.put("password", " " + PASSWORD + " ");
            Map<String, String> keyValues = keyCredentials();
            keyValues.put("privateKey", " " + PRIVATE_KEY);
            keyValues.put("privateKeyPassphrase", PASSPHRASE);

            SshConnectionConfig passwordConfig = SshConnectionConfig.fromCredentials(passwordValues);
            SshConnectionConfig keyConfig = SshConnectionConfig.fromCredentials(keyValues);

            assertEquals(" " + PASSWORD + " ", passwordConfig.password());
            assertEquals(" " + PRIVATE_KEY, keyConfig.privateKey());
            assertEquals(PASSPHRASE, keyConfig.privateKeyPassphrase());
            assertNull(passwordConfig.privateKey());
        }

        @Test
        @DisplayName("пустая парольная фраза считается отсутствующей")
        void blankPassphraseIsAbsent() {
            Map<String, String> values = keyCredentials();
            values.put("privateKeyPassphrase", "   ");

            assertNull(SshConnectionConfig.fromCredentials(values).privateKeyPassphrase());
        }

        @Test
        @DisplayName("обрезает пробелы вокруг fingerprint")
        void trimsFingerprint() {
            Map<String, String> values = credentials();
            values.put("hostKeyFingerprint", "  " + FINGERPRINT + "\n");

            assertEquals(FINGERPRINT, SshConnectionConfig.fromCredentials(values).hostKeyFingerprint());
        }

        @Test
        @DisplayName("отсутствующий fingerprint допустим: режим изучения при первой проверке")
        void absentFingerprint() {
            assertNull(SshConnectionConfig.fromCredentials(learningCredentials()).hostKeyFingerprint());
        }

        @Test
        @DisplayName("fingerprint из одних пробелов считается отсутствующим")
        void blankFingerprintIsAbsent() {
            Map<String, String> values = learningCredentials();
            values.put("hostKeyFingerprint", "   ");

            assertNull(SshConnectionConfig.fromCredentials(values).hostKeyFingerprint());
        }
    }

    @Nested
    @DisplayName("валидация")
    class Validation {

        @Test
        @DisplayName("отклоняет пустой хост")
        void blankHost() {
            Map<String, String> values = credentials();
            values.put("host", "  ");

            assertEquals("host", rejected(values).field());
        }

        @Test
        @DisplayName("отклоняет отсутствующий хост")
        void missingHost() {
            Map<String, String> values = credentials();
            values.remove("host");

            assertEquals("host", rejected(values).field());
        }

        @Test
        @DisplayName("отклоняет пустой набор credentials")
        void nullCredentials() {
            assertEquals("host", assertThrows(SshConnectionConfig.ValidationException.class,
                    () -> SshConnectionConfig.fromCredentials(null)).field());
        }

        @Test
        @DisplayName("отклоняет пустое имя пользователя")
        void blankUsername() {
            Map<String, String> values = credentials();
            values.put("username", "\t");

            assertEquals("username", rejected(values).field());
        }

        @Test
        @DisplayName("отклоняет некорректный fingerprint ключа сервера")
        void malformedFingerprint() {
            String[] malformed = {
                    "SHA256:",
                    "MD5:aa:bb:cc",
                    "sha256:" + FINGERPRINT.substring("SHA256:".length()),
                    FINGERPRINT + "=",
                    "SHA256:short"
            };

            for (String fingerprint : malformed) {
                Map<String, String> values = credentials();
                values.put("hostKeyFingerprint", fingerprint);

                assertEquals("hostKeyFingerprint", rejected(values).field(), fingerprint);
            }
        }

        @Test
        @DisplayName("отклоняет порт вне диапазона или не число")
        void invalidPort() {
            String[] invalid = {"0", "65536", "-1", "not-a-port", "22.5"};

            for (String port : invalid) {
                Map<String, String> values = credentials();
                values.put("port", port);

                assertEquals("port", rejected(values).field(), port);
            }
        }

        @Test
        @DisplayName("отклоняет одновременные ключ и пароль")
        void bothAuthMethods() {
            Map<String, String> values = keyCredentials();
            values.put("password", PASSWORD);

            assertEquals("password", rejected(values).field());
        }

        @Test
        @DisplayName("отклоняет отсутствие и ключа, и пароля")
        void noAuthMethod() {
            Map<String, String> values = credentials();
            values.remove("password");

            assertEquals("privateKey", rejected(values).field());
        }

        @Test
        @DisplayName("отклоняет парольную фразу без ключа")
        void passphraseWithoutKey() {
            Map<String, String> values = credentials();
            values.put("privateKeyPassphrase", PASSPHRASE);

            assertEquals("privateKeyPassphrase", rejected(values).field());
        }

        @Test
        @DisplayName("ошибка является ConnectorException на границе коннектора")
        void extendsConnectorException() {
            Map<String, String> values = credentials();
            values.remove("host");

            assertTrue(ConnectorException.class.isAssignableFrom(
                    SshConnectionConfig.ValidationException.class));
            assertTrue(rejected(values) instanceof ConnectorException);
        }
    }

    @Nested
    @DisplayName("секреты")
    class Secrets {

        @Test
        @DisplayName("toString не содержит пароль, ключ или парольную фразу")
        void toStringRedactsSecrets() {
            Map<String, String> values = keyCredentials();
            values.put("privateKeyPassphrase", PASSPHRASE);

            String rendered = SshConnectionConfig.fromCredentials(values).toString();

            assertFalse(rendered.contains("S3CRET-KEY-BODY"));
            assertFalse(rendered.contains(PASSPHRASE));
            assertFalse(rendered.contains("BEGIN OPENSSH PRIVATE KEY"));
        }

        @Test
        @DisplayName("toString не содержит пароль")
        void toStringRedactsPassword() {
            String rendered = SshConnectionConfig.fromCredentials(credentials()).toString();

            assertFalse(rendered.contains(PASSWORD));
        }

        @Test
        @DisplayName("сообщения валидации не повторяют значения формы")
        void messagesDoNotEchoValues() {
            Map<String, String> values = keyCredentials();
            values.put("port", "not-a-secret-but-not-a-port");

            SshConnectionConfig.ValidationException exception = rejected(values);

            assertFalse(exception.getMessage().contains("not-a-secret-but-not-a-port"));
            assertFalse(exception.getMessage().contains(PASSWORD));
            assertFalse(exception.getMessage().contains(PRIVATE_KEY));
        }
    }
}
