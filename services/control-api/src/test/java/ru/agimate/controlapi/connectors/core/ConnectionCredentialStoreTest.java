package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.CredentialField.Type;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionCredentialStore")
class ConnectionCredentialStoreTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();

    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SecretService secretService;

    private ConnectionCredentialStore store;
    private final Secret secret = Secret.builder().id(SECRET_ID).entity("connection").build();

    @BeforeEach
    void setUp() {
        IntegrationConnectorHandler handler = mock(IntegrationConnectorHandler.class);
        when(handler.connectorCode()).thenReturn("mcp");
        Map<String, CredentialField> fields = new LinkedHashMap<>();
        fields.put("url", CredentialField.required("URL", Type.URL));
        fields.put("auth_token", CredentialField.optional("Token", Type.SECRET));
        fields.put("headers", CredentialField.optional("Headers", Type.SECRET));
        lenient().when(handler.getCredentialFields()).thenReturn(fields);
        store = new ConnectionCredentialStore(new ConnectorRegistry(List.of(handler)), secretRepository, secretService);
    }

    private static Connection connection(UUID secretId) {
        return Connection.builder().id(CONNECTION_ID).connectorCode("mcp").secretId(secretId).build();
    }

    @Nested
    @DisplayName("write")
    class Write {

        @Test
        @DisplayName("открытые поля — в params, секретные и необъявленные — в секрет; URL без пробелов")
        void splitsByDeclaration() {
            Connection connection = connection(SECRET_ID);
            when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));

            store.write(connection, Map.of(
                    "url", " https://x/mcp ",
                    "auth_token", "t",
                    "headers", "{\"X-Key\":\"k\"}",
                    "oauth_access_token", "a"));

            assertEquals(Map.of("url", "https://x/mcp"), connection.getParams());
            verify(secretService).update(secret, CONNECTION_ID,
                    Map.of("auth_token", "t", "headers", "{\"X-Key\":\"k\"}", "oauth_access_token", "a"));
        }

        @Test
        @DisplayName("URL с ключом в query или userinfo остаётся в секрете")
        void urlWithKeyStaysSecret() {
            Connection connection = connection(SECRET_ID);
            when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));

            store.write(connection, Map.of("url", "https://x/mcp?api_key=k"));

            assertTrue(connection.getParams().isEmpty());
            verify(secretService).update(secret, CONNECTION_ID, Map.of("url", "https://x/mcp?api_key=k"));
        }

        @Test
        @DisplayName("первая запись заводит секрет, даже пустой")
        void createsSecret() {
            Connection connection = connection(null);
            when(secretService.store(eq("connection"), eq(CONNECTION_ID), eq(Map.of()))).thenReturn(secret);

            store.write(connection, Map.of("url", "https://x/mcp"));

            assertEquals(SECRET_ID, connection.getSecretId());
            assertEquals(Map.of("url", "https://x/mcp"), connection.getParams());
        }
    }

    @Nested
    @DisplayName("read")
    class Read {

        @Test
        @DisplayName("склеивает params и секрет в одну карту")
        void glues() {
            Connection connection = connection(SECRET_ID);
            connection.setParams(Map.of("url", "https://x/mcp"));
            when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(Map.of("auth_token", "t"));

            assertEquals(Map.of("url", "https://x/mcp", "auth_token", "t"), store.read(connection));
        }

        @Test
        @DisplayName("без секрета — только открытая часть")
        void withoutSecret() {
            Connection connection = connection(null);

            assertTrue(store.read(connection).isEmpty());
        }
    }
}
