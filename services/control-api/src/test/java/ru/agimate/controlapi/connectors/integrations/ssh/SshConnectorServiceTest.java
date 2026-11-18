package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ToolExecutionPolicy;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.database.model.ConnectorTraits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SshConnectorService")
class SshConnectorServiceTest {

    private static final String FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nS3CRET-KEY-BODY\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String PASSWORD = "PaSSword-VALUE";

    @Mock
    private SshExecutor executor;

    private SshConnectorService connector;

    @BeforeEach
    void setUp() {
        connector = new SshConnectorService(new SshToolService(executor), executor);
    }

    private static Map<String, String> keyCredentials() {
        Map<String, String> values = new HashMap<>();
        values.put("host", "example.com");
        values.put("username", "root");
        values.put("privateKey", PRIVATE_KEY);
        return values;
    }

    private static Map<String, String> passwordCredentials() {
        Map<String, String> values = keyCredentials();
        values.remove("privateKey");
        values.put("password", PASSWORD);
        return values;
    }

    @Nested
    @DisplayName("метаданные")
    class Metadata {

        @Test
        @DisplayName("код, имя, описание и множественность экземпляров")
        void identity() {
            assertEquals("ssh", connector.connectorCode());
            assertEquals("SSH", connector.connectorName());
            assertFalse(connector.connectorDescription().isBlank());
            assertTrue(connector.allowsMultipleInstances());
            assertEquals(ConnectorTraits.internal(), connector.traits());
        }

        @Test
        @DisplayName("поля идут в порядке формы; отпечатка ключа сервера в форме нет")
        void credentialFieldOrder() {
            assertEquals(List.of("host", "port", "username",
                            "privateKey", "privateKeyPassphrase", "password"),
                    new ArrayList<>(connector.getCredentialFields().keySet()));
        }

        @Test
        @DisplayName("открытые поля — TEXT, секреты замаскированы, ключ многострочный")
        void credentialFieldDeclarations() {
            Map<String, CredentialField> fields = connector.getCredentialFields();

            assertField(fields.get("host"), CredentialField.Type.TEXT, true, false);
            assertField(fields.get("port"), CredentialField.Type.TEXT, false, false);
            assertField(fields.get("username"), CredentialField.Type.TEXT, true, false);
            assertField(fields.get("privateKey"), CredentialField.Type.SECRET, false, true);
            assertField(fields.get("privateKeyPassphrase"), CredentialField.Type.SECRET, false, false);
            assertField(fields.get("password"), CredentialField.Type.SECRET, false, false);
        }

        @Test
        @DisplayName("выученный отпечаток хранится открыто в params")
        void learnedFingerprintIsOpen() {
            assertTrue(connector.openDerivedKeys().contains(SshConnectorService.FIELD_HOST_KEY_FINGERPRINT));
        }

        private static void assertField(CredentialField field, CredentialField.Type type,
                                        boolean required, boolean multiline) {
            assertEquals(type, field.type());
            assertEquals(required, field.required());
            assertEquals(multiline, field.multiline());
        }

        @Test
        @DisplayName("набор полей только для чтения")
        void credentialFieldsAreUnmodifiable() {
            assertThrows(UnsupportedOperationException.class,
                    () -> connector.getCredentialFields().put("extra", CredentialField.optional("x", CredentialField.Type.TEXT)));
        }
    }

    @Nested
    @DisplayName("executionPolicy")
    class ExecutionPolicy {

        @Test
        @DisplayName("run_command не повторяем, бюджет — таймаут команды и запас на соединение")
        void nonReplayableWithGrace() {
            ToolExecutionPolicy policy = connector.executionPolicy("run_command", Map.of("timeoutSeconds", 120));

            assertEquals(ToolExecutionPolicy.Kind.NON_REPLAYABLE, policy.kind());
            assertEquals(Duration.ofSeconds(180), policy.executionTimeout());
        }

        @Test
        @DisplayName("без таймаута в аргументах — бюджет от значения по умолчанию")
        void defaultTimeout() {
            ToolExecutionPolicy policy = connector.executionPolicy("run_command", Map.of());

            assertEquals(Duration.ofSeconds(120), policy.executionTimeout());
        }

        @Test
        @DisplayName("отсутствующий вход тоже даёт бюджет по умолчанию")
        void nullInput() {
            assertEquals(Duration.ofSeconds(120), connector.executionPolicy("run_command", null).executionTimeout());
        }

        @Test
        @DisplayName("максимальный таймаут — бюджет 1860 секунд")
        void maxTimeout() {
            assertEquals(Duration.ofSeconds(1860),
                    connector.executionPolicy("run_command", Map.of("timeoutSeconds", 1800)).executionTimeout());
        }

        @Test
        @DisplayName("некорректный таймаут падает до обращения к провайдеру")
        void invalidTimeout() {
            assertThrows(ConnectorException.class,
                    () -> connector.executionPolicy("run_command", Map.of("timeoutSeconds", 0)));
            assertThrows(ConnectorException.class,
                    () -> connector.executionPolicy("run_command", Map.of("timeoutSeconds", "soon")));

            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("прочие тулы наследуют политику по умолчанию — повторяемые")
        void otherToolsAreReplayable() {
            ToolExecutionPolicy policy = connector.executionPolicy("other_tool", Map.of("timeoutSeconds", 5));

            assertEquals(ToolExecutionPolicy.Kind.REPLAYABLE, policy.kind());
        }
    }

    @Nested
    @DisplayName("validateCredentials")
    class ValidateCredentials {

        @Test
        @DisplayName("успех: только validate, без команды, с каноническими host/port и выученным отпечатком")
        void success() {
            when(executor.validate(any(SshConnectionConfig.class))).thenReturn(FINGERPRINT);
            Map<String, String> credentials = keyCredentials();
            credentials.put("host", "Example.COM");
            credentials.put("username", "Root");

            IntegrationValidationResult result = connector.validateCredentials(credentials);

            assertEquals(IntegrationValidationResult.Outcome.VALID, result.outcome());
            assertEquals("Root@example.com:22", result.identifier());
            assertEquals("SSH: Root@example.com:22", result.displayName());
            assertEquals(Map.of("host", "example.com", "port", "22",
                            "hostKeyFingerprint", FINGERPRINT),
                    result.derivedCredentials());
            verify(executor).validate(any(SshConnectionConfig.class));
            verify(executor, never()).execute(any(), any(), any());
        }

        @Test
        @DisplayName("успех с явным портом")
        void explicitPort() {
            when(executor.validate(any(SshConnectionConfig.class))).thenReturn(FINGERPRINT);
            Map<String, String> credentials = passwordCredentials();
            credentials.put("port", "2222");

            IntegrationValidationResult result = connector.validateCredentials(credentials);

            assertEquals("root@example.com:2222", result.identifier());
            assertEquals(Map.of("host", "example.com", "port", "2222",
                    "hostKeyFingerprint", FINGERPRINT), result.derivedCredentials());
        }

        @Test
        @DisplayName("неполная форма: поле из ValidationException, без обращения к сети")
        void incompleteForm() {
            Map<String, String> credentials = keyCredentials();
            credentials.remove("privateKey");

            IntegrationValidationResult result = connector.validateCredentials(credentials);

            assertFalse(result.valid());
            assertEquals("privateKey", result.errorField());
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("ключ и пароль одновременно — ошибка поля password")
        void bothAuthMethods() {
            Map<String, String> credentials = keyCredentials();
            credentials.put("password", PASSWORD);

            IntegrationValidationResult result = connector.validateCredentials(credentials);

            assertEquals("password", result.errorField());
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("несовпадение отпечатка ключа хоста — поле host, в сообщении expected/actual")
        void hostKeyMismatch() {
            doThrow(new ConnectorException(SshExecutor.HOST_KEY_MISMATCH + " (expected a / actual b)"))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertFalse(result.valid());
            assertEquals("host", result.errorField());
            assertTrue(result.errorMessage().startsWith(SshExecutor.HOST_KEY_MISMATCH));
            assertTrue(result.errorMessage().contains("expected a / actual b"));
        }

        @Test
        @DisplayName("сбой аутентификации по ключу — поле privateKey")
        void keyAuthenticationFailure() {
            doThrow(new ConnectorException(SshExecutor.AUTHENTICATION_FAILED))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertEquals("privateKey", result.errorField());
            assertEquals(SshExecutor.AUTHENTICATION_FAILED, result.errorMessage());
        }

        @Test
        @DisplayName("сбой аутентификации по паролю — поле password")
        void passwordAuthenticationFailure() {
            doThrow(new ConnectorException(SshExecutor.AUTHENTICATION_FAILED))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(passwordCredentials());

            assertEquals("password", result.errorField());
        }

        @Test
        @DisplayName("нечитаемый ключ или неверная парольная фраза — поле privateKey")
        void unreadablePrivateKey() {
            doThrow(new ConnectorException(SshKeyLoader.GENERIC_MESSAGE))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertEquals("privateKey", result.errorField());
            assertEquals(SshKeyLoader.GENERIC_MESSAGE, result.errorMessage());
        }

        @Test
        @DisplayName("недоступный хост — поле host")
        void connectionFailure() {
            doThrow(new ConnectorException("Unable to establish an SSH connection"))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertEquals("host", result.errorField());
            assertEquals("Unable to establish an SSH connection", result.errorMessage());
        }

        @Test
        @DisplayName("сообщение об ошибке не повторяет секреты формы")
        void failuresDoNotEchoSecrets() {
            doThrow(new ConnectorException(SshExecutor.AUTHENTICATION_FAILED))
                    .when(executor).validate(any(SshConnectionConfig.class));

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertFalse(result.errorMessage().contains("S3CRET-KEY-BODY"));
            assertFalse(result.errorMessage().contains(PASSWORD));
        }

        @Test
        @DisplayName("executor не сообщил отпечаток — INVALID на поле host")
        void missingLearnedFingerprintIsInvalid() {
            when(executor.validate(any(SshConnectionConfig.class))).thenReturn(null);

            IntegrationValidationResult result = connector.validateCredentials(keyCredentials());

            assertFalse(result.valid());
            assertEquals("host", result.errorField());
            assertEquals(SshExecutor.HOST_KEY_NOT_REPORTED, result.errorMessage());
        }

        @Test
        @DisplayName("сохранённый отпечаток из params проверяется как закреплённый")
        void storedFingerprintIsPinned() {
            when(executor.validate(any(SshConnectionConfig.class))).thenReturn(FINGERPRINT);
            Map<String, String> credentials = keyCredentials();
            credentials.put("hostKeyFingerprint", FINGERPRINT);

            IntegrationValidationResult result = connector.validateCredentials(credentials);

            assertTrue(result.valid());
            verify(executor).validate(argThat((SshConnectionConfig config) ->
                    FINGERPRINT.equals(config.hostKeyFingerprint())));
            verify(executor, never()).execute(any(), any(), any());
        }
    }
}
