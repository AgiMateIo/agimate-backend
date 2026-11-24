package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpOAuthService")
class McpOAuthServiceTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String REFRESH_TOKEN = "refresh-1";
    private static final String CLIENT_ID = "client-1";
    private static final String CLIENT_SECRET = "secret-1";
    private static final String CIMD_ID = "https://agimate.example/client.json";
    private static final String SCOPE = "read";

    @Mock
    private McpOAuthStore store;
    @Mock
    private McpOAuthClient client;
    @Mock
    private DynamicClientRegistrar registrar;
    @Mock
    private OAuthRegistrationLimiter registrationLimiter;

    private McpOAuthProperties properties;
    private McpOAuthService service;
    private Connection connection;

    @BeforeEach
    void setUp() {
        properties = new McpOAuthProperties();
        properties.setClientId(CIMD_ID);
        properties.setRedirectUri("https://agimate.example/oauth/callback");
        service = new McpOAuthService(store, client, registrar, registrationLimiter, properties);
        connection = Connection.builder().id(CONNECTION_ID).connectorCode("mcp")
                .userId(UUID.randomUUID()).build();
    }

    private Map<String, String> credentials() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("url", "https://mcp.example.com/mcp");
        values.put(OAuthCredentials.ISSUER, "https://auth.example.com");
        values.put(OAuthCredentials.TOKEN_ENDPOINT, "https://auth.example.com/token");
        values.put(OAuthCredentials.AUTHORIZATION_ENDPOINT, "https://auth.example.com/authorize");
        values.put(OAuthCredentials.SCOPE_REQUESTED, SCOPE);
        values.put(OAuthCredentials.REGISTRATION_ENDPOINT, "https://auth.example.com/register");
        return values;
    }

    private Map<String, String> cimd() {
        Map<String, String> values = credentials();
        values.put(OAuthCredentials.CLIENT_SOURCE, "CIMD");
        return values;
    }

    private Map<String, String> dcr() {
        Map<String, String> values = credentials();
        values.put(OAuthCredentials.CLIENT_SOURCE, "DCR");
        return values;
    }

    private void grantClient(Map<String, String> values, String id, String scope) {
        values.put(OAuthCredentials.CLIENT_ID, id);
        values.put(OAuthCredentials.CLIENT_SECRET, CLIENT_SECRET);
        values.put(OAuthCredentials.CLIENT_AUTH_METHOD, "client_secret_basic");
        if (scope != null) {
            values.put(OAuthCredentials.CLIENT_SCOPE, scope);
        }
        values.put(OAuthCredentials.CLIENT_SECRET_EXPIRES_AT,
                Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()));
    }

    private void flowClient(Map<String, String> values, String id, String scope) {
        values.put(OAuthCredentials.FLOW_CLIENT_SOURCE, "DCR");
        values.put(OAuthCredentials.FLOW_CLIENT_ID, id);
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET, CLIENT_SECRET);
        values.put(OAuthCredentials.FLOW_CLIENT_AUTH_METHOD, "client_secret_basic");
        if (scope != null) {
            values.put(OAuthCredentials.FLOW_CLIENT_SCOPE, scope);
        }
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET_EXPIRES_AT,
                Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()));
    }

    private OAuthTokens tokens() {
        return new OAuthTokens("access-2", "refresh-2", LocalDateTime.now().plusHours(1), SCOPE);
    }

    private void dueForRefresh() {
        connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(store.connection(CONNECTION_ID)).thenReturn(connection);
    }

    @Nested
    @DisplayName("tracksExpiry")
    class TracksExpiry {

        @Test
        @DisplayName("срок записан — есть за чем следить")
        void withExpiry() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusHours(1));
            when(store.findConnection(CONNECTION_ID)).thenReturn(Optional.of(connection));

            assertTrue(service.tracksExpiry(CONNECTION_ID));
        }

        @Test
        @DisplayName("срока нет — статический токен или AS без expires_in")
        void withoutExpiry() {
            when(store.findConnection(CONNECTION_ID)).thenReturn(Optional.of(connection));

            assertFalse(service.tracksExpiry(CONNECTION_ID));
        }

        @Test
        @DisplayName("коннекции нет — false, а не исключение: ресинк на старте удалит осиротевшую строку")
        void connectionGone() {
            when(store.findConnection(CONNECTION_ID)).thenReturn(Optional.empty());

            assertFalse(service.tracksExpiry(CONNECTION_ID));
        }
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
            dueForRefresh();
            when(store.credentials(connection)).thenReturn(credentials());

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("CIMD: идентичность из конфига, клиент гранта не нужен")
        void cimdRefreshes() {
            dueForRefresh();
            Map<String, String> credentials = cimd();
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq(CIMD_ID), eq(REFRESH_TOKEN), any()))
                    .thenReturn(true);

            assertTrue(service.refreshIfNeeded(CONNECTION_ID));
            verify(client).refresh(any(), eq(new ClientIdentity(OAuthClientSource.CIMD, CIMD_ID, null,
                    TokenEndpointAuthMethod.NONE)), eq(REFRESH_TOKEN));
        }

        @Test
        @DisplayName("CIMD: сохранённый в гранте client_id важнее текущего конфига")
        void cimdSavedClientIdWins() {
            dueForRefresh();
            Map<String, String> credentials = cimd();
            credentials.put(OAuthCredentials.CLIENT_ID, "saved-client");
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq("saved-client"), eq(REFRESH_TOKEN), any()))
                    .thenReturn(true);

            assertTrue(service.refreshIfNeeded(CONNECTION_ID));
            verify(client).refresh(any(), eq(new ClientIdentity(OAuthClientSource.CIMD, "saved-client", null,
                    TokenEndpointAuthMethod.NONE)), eq(REFRESH_TOKEN));
        }

        @Test
        @DisplayName("DCR без клиента — AUTH_EXPIRED без сети и без регистрации")
        void dcrWithoutClientExpires() {
            dueForRefresh();
            Map<String, String> credentials = dcr();
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verifyNoInteractions(client);
            verify(registrar, never()).register(any());
            verify(store).expireWithoutClient(CONNECTION_ID);
        }

        @Test
        @DisplayName("DCR: обмен идентичностью гранта, запись сверяет пару client_id + refresh")
        void dcrRefreshes() {
            dueForRefresh();
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq(CLIENT_ID), eq(REFRESH_TOKEN), any()))
                    .thenReturn(true);

            assertTrue(service.refreshIfNeeded(CONNECTION_ID));
            verify(registrar, never()).register(any());
        }

        @Test
        @DisplayName("запись отвергнута (успели переавторизоваться) — не считаем это обновлением")
        void staleGrantDiscarded() {
            dueForRefresh();
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN))).thenReturn(tokens());
            when(store.storeRefreshed(eq(CONNECTION_ID), eq(CLIENT_ID), eq(REFRESH_TOKEN), any()))
                    .thenReturn(false);

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
        }

        @Test
        @DisplayName("invalid_grant — стираем refresh, клиента оставляем")
        void grantRejected() {
            dueForRefresh();
            Map<String, String> credentials = cimd();
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN)))
                    .thenThrow(new OAuthGrantRejectedException("revoked"));

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verify(store).rejectRefresh(CONNECTION_ID, REFRESH_TOKEN);
            verify(store, never()).rejectRefreshClient(any(), any());
        }

        @Test
        @DisplayName("invalid_client — стираем и refresh, и клиента, сохраняя source")
        void clientRejected() {
            dueForRefresh();
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN)))
                    .thenThrow(new OAuthClientAuthenticationException("rejected"));

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verify(store).rejectRefreshClient(CONNECTION_ID, REFRESH_TOKEN);
            verify(store, never()).rejectRefresh(any(), any());
        }

        @Test
        @DisplayName("invalid_scope на refresh — клиент с неподходящим scope тоже заменяется")
        void scopeRejected() {
            dueForRefresh();
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN)))
                    .thenThrow(new OAuthScopeRejectedException("scope"));

            assertFalse(service.refreshIfNeeded(CONNECTION_ID));
            verify(store).rejectRefreshClient(CONNECTION_ID, REFRESH_TOKEN, true);
        }

        @Test
        @DisplayName("сетевой отказ пробрасывается — джоба повторит, статус не трогаем")
        void transientFailurePropagates() {
            dueForRefresh();
            Map<String, String> credentials = cimd();
            credentials.put(OAuthCredentials.REFRESH_TOKEN, REFRESH_TOKEN);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.refresh(any(), any(), eq(REFRESH_TOKEN)))
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
            when(store.credentials(connection)).thenReturn(cimd());

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> service.completeAuthorization(connection, "code", "https://evil.example"));

            assertTrue(e.getMessage().contains("different issuer"));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("старый CIMD-флоу без flow-ключей — идентичность из конфига")
        void oldCimdFlow() {
            Map<String, String> credentials = cimd();
            credentials.put(OAuthCredentials.CODE_VERIFIER, "verifier");
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.exchangeCode(any(), any(), eq("code"), eq("verifier"))).thenReturn(tokens());

            service.completeAuthorization(connection, "code", null);

            verify(client).exchangeCode(any(), eq(new ClientIdentity(OAuthClientSource.CIMD, CIMD_ID, null,
                    TokenEndpointAuthMethod.NONE)), eq("code"), eq("verifier"));
            verify(store).storeGrant(eq(connection), any(), any());
        }

        @Test
        @DisplayName("DCR-флоу обменивает код идентичностью потока, а не гранта")
        void dcrFlow() {
            Map<String, String> credentials = dcr();
            grantClient(credentials, "grant-client", SCOPE);
            flowClient(credentials, "flow-client", SCOPE);
            credentials.put(OAuthCredentials.CODE_VERIFIER, "verifier");
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.exchangeCode(any(), any(), eq("code"), eq("verifier"))).thenReturn(tokens());

            service.completeAuthorization(connection, "code", null);

            verify(client).exchangeCode(any(),
                    eq(new ClientIdentity(OAuthClientSource.DCR, "flow-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC)),
                    eq("code"), eq("verifier"));
        }

        @Test
        @DisplayName("флоу не начинали — verifier'а нет, обменивать нечего")
        void withoutVerifier() {
            when(store.credentials(connection)).thenReturn(cimd());

            assertThrows(ConnectorException.class,
                    () -> service.completeAuthorization(connection, "code", null));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("invalid_client на обмене — отвергнутый клиент вычищается вне отката")
        void invalidClientCleans() {
            Map<String, String> credentials = dcr();
            flowClient(credentials, "flow-client", SCOPE);
            credentials.put(OAuthCredentials.CODE_VERIFIER, "verifier");
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.exchangeCode(any(), any(), eq("code"), eq("verifier")))
                    .thenThrow(new OAuthClientAuthenticationException("rejected"));

            assertThrows(OAuthClientAuthenticationException.class,
                    () -> service.completeAuthorization(connection, "code", null));

            verify(store).clearRejectedClient(CONNECTION_ID, false);
            verify(store, never()).storeGrant(any(), any(), any());
        }

        @Test
        @DisplayName("invalid_scope на обмене — та же очистка: иначе следующий старт повторит отказ")
        void invalidScopeCleans() {
            Map<String, String> credentials = dcr();
            flowClient(credentials, "flow-client", SCOPE);
            credentials.put(OAuthCredentials.CODE_VERIFIER, "verifier");
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.exchangeCode(any(), any(), eq("code"), eq("verifier")))
                    .thenThrow(new OAuthScopeRejectedException("scope"));

            assertThrows(OAuthScopeRejectedException.class,
                    () -> service.completeAuthorization(connection, "code", null));

            verify(store).clearRejectedClient(CONNECTION_ID, true);
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
        @DisplayName("CIMD: state и verifier рождаются здесь, flow-ключи — из конфига")
        @SuppressWarnings("unchecked")
        void cimdMintsState() {
            when(store.credentials(connection)).thenReturn(cimd());
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            assertEquals("https://auth.example.com/go", service.startAuthorization(connection));

            ArgumentCaptor<Map<String, String>> flow = ArgumentCaptor.forClass(Map.class);
            verify(store).startFlow(eq(CONNECTION_ID), any(), any(), any(), flow.capture());
            assertEquals("CIMD", flow.getValue().get(OAuthCredentials.FLOW_CLIENT_SOURCE));
            assertEquals(CIMD_ID, flow.getValue().get(OAuthCredentials.FLOW_CLIENT_ID));
            verifyNoInteractions(registrar);
        }

        @Test
        @DisplayName("CIMD без client-id — понятный отказ до сети")
        void cimdWithoutClientIdFails() {
            properties.setClientId("");
            when(store.credentials(connection)).thenReturn(cimd());

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> service.startAuthorization(connection));

            assertTrue(e.getMessage().contains("client-id"));
            verifyNoInteractions(registrar);
        }

        @Test
        @DisplayName("DCR переиспользует клиента гранта, если тот покрывает scope")
        void reusesGrantClient() {
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar, never()).register(any());
            verify(client).authorizationUrl(any(),
                    eq(new ClientIdentity(OAuthClientSource.DCR, CLIENT_ID, CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC)), any(), any());
        }

        @Test
        @DisplayName("вторая брошенная попытка переиспользует клиента первой, а не регистрирует нового")
        void reusesFlowClient() {
            Map<String, String> credentials = dcr();
            flowClient(credentials, "flow-client", SCOPE);
            when(store.credentials(connection)).thenReturn(credentials);
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar, never()).register(any());
            verify(client).authorizationUrl(any(),
                    eq(new ClientIdentity(OAuthClientSource.DCR, "flow-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC)), any(), any());
        }

        @Test
        @DisplayName("DCR без клиента регистрирует нового и кладёт его только в flow")
        void registersWhenNoClient() {
            when(store.credentials(connection)).thenReturn(dcr());
            when(registrar.register(any())).thenReturn(new RegisteredClient(
                    new ClientIdentity(OAuthClientSource.DCR, "new-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    SCOPE, null));
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar).register(any());
            verify(client).authorizationUrl(any(),
                    eq(new ClientIdentity(OAuthClientSource.DCR, "new-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC)), any(), any());
        }

        @Test
        @DisplayName("scope не покрыт — новая регистрация, даже если клиент гранта есть")
        void registersForWiderScope() {
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, "read");
            credentials.put(OAuthCredentials.SCOPE_REQUESTED, "read write");
            when(store.credentials(connection)).thenReturn(credentials);
            when(registrar.register(any())).thenReturn(new RegisteredClient(
                    new ClientIdentity(OAuthClientSource.DCR, "new-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    "read write", null));
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar).register(any());
        }

        @Test
        @DisplayName("пустой зарегистрированный scope годится только при пустом запросе")
        void emptyRegisteredScope() {
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, null);
            credentials.put(OAuthCredentials.SCOPE_REQUESTED, "read");
            when(store.credentials(connection)).thenReturn(credentials);
            when(registrar.register(any())).thenReturn(new RegisteredClient(
                    new ClientIdentity(OAuthClientSource.DCR, "new-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    SCOPE, null));
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar).register(any());
        }

        @Test
        @DisplayName("истёкший секрет клиента гранта — новая регистрация")
        void expiredSecretRegisters() {
            Map<String, String> credentials = dcr();
            grantClient(credentials, CLIENT_ID, SCOPE);
            credentials.put(OAuthCredentials.CLIENT_SECRET_EXPIRES_AT,
                    Long.toString(Instant.now().minusSeconds(60).getEpochSecond()));
            when(store.credentials(connection)).thenReturn(credentials);
            when(registrar.register(any())).thenReturn(new RegisteredClient(
                    new ClientIdentity(OAuthClientSource.DCR, "new-client", CLIENT_SECRET,
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    SCOPE, null));
            when(client.authorizationUrl(any(), any(), any(), any())).thenReturn("https://auth.example.com/go");

            service.startAuthorization(connection);

            verify(registrar).register(any());
        }
    }

    @Nested
    @DisplayName("очистка по ошибке callback")
    class Reject {

        @Test
        @DisplayName("invalid_scope и invalid_client чистят клиента, access_denied — нет")
        void onlyRejectedClientErrors() {
            service.rejectAuthorization(connection, "invalid_scope");
            service.rejectAuthorization(connection, "invalid_client");
            service.rejectAuthorization(connection, "access_denied");

            verify(store).clearRejectedClient(CONNECTION_ID, true);
            verify(store).clearRejectedClient(CONNECTION_ID, false);
        }
    }
}
