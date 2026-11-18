package ru.agimate.controlapi.service.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.connectors.core.ConnectionCredentialStore;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.integrations.ssh.SshConnectorService;
import ru.agimate.controlapi.connectors.integrations.ssh.SshExecutor;
import ru.agimate.controlapi.connectors.integrations.ssh.SshToolService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionService — удаление подключения")
class ConnectionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private ConnectorEnvFactory envFactory;
    @Mock
    private SecretService secretService;
    @Mock
    private ConnectionCredentialStore credentialStore;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AppService appService;
    @Mock
    private SshExecutor sshExecutor;

    @InjectMocks
    private ConnectionService service;

    @Test
    @DisplayName("APP-подключение удаляется через AppService — строка apps и навык не остаются")
    void appConnectionDelegatesToAppService() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID).userId(USER_ID).connectorCode("app").appId(APP_ID).build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));

        service.delete(CONNECTION_ID, USER_ID);

        verify(appService).deleteApp(APP_ID, USER_ID);
        verify(connectionRepository, never()).softDelete(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("обычное подключение по-прежнему мягко удаляется общим путём")
    void integrationConnectionKeepsTheGenericPath() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID).userId(USER_ID).connectorCode("mcp").build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));

        service.delete(CONNECTION_ID, USER_ID);

        verify(connectionRepository).softDelete(eq(CONNECTION_ID), any());
        verify(eventPublisher).publishEvent(any(ConnectorDeletedEvent.class));
        verifyNoInteractions(appService);
    }

    @Nested
    @DisplayName("updateSecret — производные credentials")
    class UpdateSecret {

        private static final String FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
        private static final String OTHER_FINGERPRINT = "SHA256:HWWZq7WaZg2J/vjQggH4YuEAUVFyEwmNzQYNmZXyzv8";
        private static final String PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nS3CRET-KEY-BODY\n-----END OPENSSH PRIVATE KEY-----\n";

        private static Map<String, String> fullCredentials() {
            Map<String, String> values = new HashMap<>();
            values.put("host", "Example.COM");
            values.put("username", "Root");
            values.put("hostKeyFingerprint", FINGERPRINT);
            values.put("privateKey", PRIVATE_KEY);
            return values;
        }

        private void sshHandler() {
            when(connectorRegistry.findIntegrationHandler("ssh"))
                    .thenReturn(Optional.of(new SshConnectorService(new SshToolService(sshExecutor), sshExecutor)));
        }

        private Connection existingConnection(String subCode) {
            Connection connection = Connection.builder()
                    .id(CONNECTION_ID).userId(USER_ID).connectorCode("ssh").subCode(subCode).build();
            when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
            when(credentialStore.read(connection)).thenReturn(Map.of());
            return connection;
        }

        @Test
        @DisplayName("полный набор с прежним subCode — validate, затем запись с каноническими host/port")
        void writesAfterValidation() {
            sshHandler();
            Connection connection = existingConnection("Root@example.com:22");

            service.updateSecret(CONNECTION_ID, USER_ID, fullCredentials());

            InOrder order = inOrder(sshExecutor, credentialStore);
            order.verify(sshExecutor).validate(any());
            order.verify(credentialStore).write(eq(connection), argThat((Map<String, String> written) ->
                    "example.com".equals(written.get("host"))
                            && "22".equals(written.get("port"))
                            && "Root".equals(written.get("username"))
                            && PRIVATE_KEY.equals(written.get("privateKey"))));
        }

        @Test
        @DisplayName("в credentialStore.write уходит канонический host и порт, а не введённый Example.COM")
        @SuppressWarnings("unchecked")
        void writesCanonicalHostAndPortToStore() {
            sshHandler();
            Connection connection = existingConnection("Root@example.com:22");

            service.updateSecret(CONNECTION_ID, USER_ID, fullCredentials());

            ArgumentCaptor<Map<String, String>> written = ArgumentCaptor.forClass(Map.class);
            verify(credentialStore).write(eq(connection), written.capture());
            assertEquals("Root", written.getValue().get("username"));
            assertEquals("example.com", written.getValue().get("host"));
            assertEquals("22", written.getValue().get("port"));
        }

        @Test
        @DisplayName("изменённый host — BadRequest, запись не выполняется")
        void differentHostIsRefused() {
            sshHandler();
            existingConnection("root@example.com:22");
            Map<String, String> credentials = fullCredentials();
            credentials.put("username", "root");
            credentials.put("host", "other.example.com");

            assertThrows(BadRequestStatusException.class,
                    () -> service.updateSecret(CONNECTION_ID, USER_ID, credentials));

            verify(sshExecutor).validate(any());
            verify(credentialStore, never()).write(any(), any());
        }

        @Test
        @DisplayName("изменённый username — BadRequest, запись не выполняется")
        void differentUsernameIsRefused() {
            sshHandler();
            existingConnection("root@example.com:22");
            Map<String, String> credentials = fullCredentials();
            credentials.put("username", "Admin");

            assertThrows(BadRequestStatusException.class,
                    () -> service.updateSecret(CONNECTION_ID, USER_ID, credentials));

            verify(credentialStore, never()).write(any(), any());
        }

        @Test
        @DisplayName("неполный набор — validation failure, запись не выполняется, params не меняются")
        void partialInputNeverWrites() {
            sshHandler();
            Connection connection = existingConnection("root@example.com:22");
            Map<String, String> previous = Map.of("host", "example.com", "port", "22", "username", "root");
            connection.setParams(previous);
            Map<String, String> credentials = fullCredentials();
            credentials.remove("privateKey");

            assertThrows(ValidationErrorStatusException.class,
                    () -> service.updateSecret(CONNECTION_ID, USER_ID, credentials));

            verifyNoInteractions(sshExecutor);
            verify(credentialStore, never()).write(any(), any());
            assertEquals(previous, connection.getParams());
        }

        @Test
        @DisplayName("новый fingerprint с полным секретом — повторная validate и запись")
        void newFingerprintRevalidatesAndWrites() {
            sshHandler();
            Connection connection = existingConnection("Root@example.com:22");
            Map<String, String> credentials = fullCredentials();
            credentials.put("hostKeyFingerprint", OTHER_FINGERPRINT);

            service.updateSecret(CONNECTION_ID, USER_ID, credentials);

            verify(sshExecutor).validate(any());
            verify(credentialStore).write(eq(connection), argThat((Map<String, String> written) ->
                    OTHER_FINGERPRINT.equals(written.get("hostKeyFingerprint"))));
        }
    }
}
