package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthService")
class McpOAuthServiceTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String REFRESH_TOKEN = "refresh-1";

    @Mock
    private McpOAuthStore store;
    @Mock
    private McpOAuthClient client;

    private McpOAuthService service;
    private Connection connection;

    @BeforeEach
    void setUp() {
        service = new McpOAuthService(store, client);
        connection = Connection.builder().id(CONNECTION_ID).connectorCode("mcp").build();
    }

    private Map<String, String> credentials(String refreshToken) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("url", "https://mcp.example.com/mcp");
        values.put(OAuthCredentials.ISSUER, "https://auth.example.com");
        values.put(OAuthCredentials.TOKEN_ENDPOINT, "https://auth.example.com/token");
        values.put(OAuthCredentials.AUTHORIZATION_ENDPOINT, "https://auth.example.com/authorize");
        if (refreshToken != null) {
            values.put(OAuthCredentials.REFRESH_TOKEN, refreshToken);
        }
        return values;
    }

    private OAuthTokens tokens() {
        return new OAuthTokens("access-2", "refresh-2", LocalDateTime.now().plusHours(1), "read");
    }

    @Nested
    @DisplayName("refreshIfNeeded")
    class Refresh {

        @Test
        @DisplayName("срок не наступил — в сеть не ходим вовсе")
        void notYet() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusHours(2));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("срока нет (статический токен или AS не прислал expires_in) — no-op")
        void noExpiry() {
            when(store.connection(CONNECTION_ID)).thenReturn(connection);

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("refresh-токена не выдавали — обновлять нечем")
        void noRefreshToken() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);
            when(store.credentials(connection)).thenReturn(credentials(null));

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("токен истекает — обмениваем и отдаём результат записи")
        void refreshes() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);
            when(store.credentials(connection)).thenReturn(credentials(REFRESH_TOKEN));
            when(client.refresh(any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq(REFRESH_TOKEN), any())).thenReturn(true);

            assertTrue(service.refreshIfNeeded(CONNECTION_ID));
        }

        @Test
        @DisplayName("запись отвергнута (успели переавторизоваться) — не считаем это обновлением")
        void staleGrantDiscarded() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);
            when(store.credentials(connection)).thenReturn(credentials(REFRESH_TOKEN));
            when(client.refresh(any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq(REFRESH_TOKEN), any())).thenReturn(false);

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
        }

        @Test
        @DisplayName("invalid_grant — коннекция уходит в AUTH_EXPIRED")
        void grantRejected() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);
            when(store.credentials(connection)).thenReturn(credentials(REFRESH_TOKEN));
            when(client.refresh(any(), eq(REFRESH_TOKEN)))
                    .thenThrow(new OAuthGrantRejectedException("revoked"));

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verify(store).markExpired(CONNECTION_ID);
        }

        @Test
        @DisplayName("сетевой отказ пробрасывается — джоба повторит, статус не трогаем")
        void transientFailurePropagates() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(store.connection(CONNECTION_ID)).thenReturn(connection);
            when(store.credentials(connection)).thenReturn(credentials(REFRESH_TOKEN));
            when(client.refresh(any(), eq(REFRESH_TOKEN)))
                    .thenThrow(new ConnectorException("Token endpoint is unreachable"));

            assertThrows(ConnectorException.class, () -> service.refreshIfNeeded(CONNECTION_ID));
            verify(store, never()).markExpired(any());
        }
    }

    @Nested
    @DisplayName("завершение авторизации")
    class Complete {

        @Test
        @DisplayName("iss не совпал с записанным issuer — отказ до обмена кода")
        void issuerMismatch() {
            when(store.credentials(connection)).thenReturn(credentials(null));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> service.completeAuthorization(connection, "code", "https://evil.example"));

            assertTrue(e.getMessage().contains("different issuer"));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("iss не пришёл вовсе — продолжаем: сервер его и не объявлял")
        void issuerAbsent() {
            Map<String, String> credentials = credentials(null);
            credentials.put(OAuthCredentials.CODE_VERIFIER, "verifier");
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.exchangeCode(any(), eq("code"), eq("verifier"))).thenReturn(tokens());

            service.completeAuthorization(connection, "code", null);

            verify(store).storeGrant(eq(connection), any(), any());
        }

        @Test
        @DisplayName("флоу не начинали — verifier'а нет, обменивать нечего")
        void withoutVerifier() {
            when(store.credentials(connection)).thenReturn(credentials(null));

            assertThrows(ConnectorException.class,
                    () -> service.completeAuthorization(connection, "code", null));
            verifyNoInteractions(client);
        }
    }

    @Nested
    @DisplayName("startAuthorization")
    class Start {

        @Test
        @DisplayName("коннекция со статическим токеном — не наш путь")
        void notOAuth() {
            when(store.credentials(connection)).thenReturn(Map.of("url", "https://mcp.example.com/mcp"));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> service.startAuthorization(connection));
            assertTrue(e.getMessage().contains("does not use OAuth"));
        }

        @Test
        @DisplayName("state и verifier рождаются здесь, а не при создании коннекции")
        void mintsState() {
            when(store.credentials(connection)).thenReturn(credentials(null));
            when(client.authorizationUrl(any(), any(), any())).thenReturn("https://auth.example.com/go");

            assertEquals("https://auth.example.com/go", service.startAuthorization(connection));
            verify(store).startFlow(eq(connection), any(), any(), any());
        }
    }
}
