package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthStore")
class McpOAuthStoreTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SecretService secretService;

    private McpOAuthStore store;
    private Connection connection;
    private Secret secret;

    @BeforeEach
    void setUp() {
        store = new McpOAuthStore(connectionRepository, secretRepository, secretService);
        connection = Connection.builder()
                .id(CONNECTION_ID)
                .connectorCode("mcp")
                .secretId(SECRET_ID)
                .authStatus(ConnectionAuthStatus.AUTHORIZED)
                .build();
        secret = new Secret();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));
    }

    @Nested
    @DisplayName("rejectRefresh")
    class RejectRefresh {

        @Test
        @DisplayName("в секрете всё ещё отвергнутый токен — стираем его и помечаем AUTH_EXPIRED")
        @SuppressWarnings("unchecked")
        void erasesRejectedToken() {
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(Map.of(
                    "url", "https://mcp.example.com/mcp",
                    OAuthCredentials.ACCESS_TOKEN, "access-1",
                    OAuthCredentials.REFRESH_TOKEN, "refresh-1"));

            assertTrue(store.rejectRefresh(CONNECTION_ID, "refresh-1"));

            ArgumentCaptor<Map<String, String>> written = ArgumentCaptor.forClass(Map.class);
            verify(secretService).update(eq(secret), eq(CONNECTION_ID), written.capture());
            assertFalse(written.getValue().containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertEquals("https://mcp.example.com/mcp", written.getValue().get("url"));
            assertEquals(ConnectionAuthStatus.AUTH_EXPIRED, connection.getAuthStatus());
        }

        @Test
        @DisplayName("пока шёл обмен, пользователь переавторизовался — новую цепочку не трогаем")
        void keepsNewerGrant() {
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(Map.of(
                    OAuthCredentials.REFRESH_TOKEN, "refresh-2"));

            assertFalse(store.rejectRefresh(CONNECTION_ID, "refresh-1"));

            verify(secretService, never()).update(any(), any(), anyMap());
            verify(connectionRepository, never()).save(any());
            assertEquals(ConnectionAuthStatus.AUTHORIZED, connection.getAuthStatus());
        }
    }
}
