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
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.controlapi.connectors.core.ConnectionCredentialStore;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpOAuthStore")
class McpOAuthStoreTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();
    private static final String CLIENT_ID = "client-1";
    private static final String CLIENT_SECRET = "secret-1";

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SecretService secretService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private McpOAuthStore store;
    private Connection connection;
    private Secret secret;

    @BeforeEach
    void setUp() {
        store = new McpOAuthStore(connectionRepository, secretRepository, secretService,
                new ConnectionCredentialStore(new ConnectorRegistry(List.of()), secretRepository, secretService),
                eventPublisher);
        connection = Connection.builder()
                .id(CONNECTION_ID)
                .connectorCode("mcp")
                .userId(UUID.randomUUID())
                .secretId(SECRET_ID)
                .authStatus(ConnectionAuthStatus.AUTHORIZED)
                .build();
        secret = new Secret();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionRepository.findByIdNotDeletedForUpdate(CONNECTION_ID))
                .thenReturn(Optional.of(connection));
        when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));
    }

    private Map<String, String> dcrWithGrant() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("url", "https://mcp.example.com/mcp");
        values.put(OAuthCredentials.CLIENT_SOURCE, "DCR");
        values.put(OAuthCredentials.CLIENT_ID, CLIENT_ID);
        values.put(OAuthCredentials.CLIENT_SECRET, CLIENT_SECRET);
        values.put(OAuthCredentials.CLIENT_AUTH_METHOD, "client_secret_basic");
        values.put(OAuthCredentials.CLIENT_SCOPE, "read");
        values.put(OAuthCredentials.CLIENT_SECRET_EXPIRES_AT,
                Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()));
        values.put(OAuthCredentials.REFRESH_TOKEN, "refresh-1");
        values.put(OAuthCredentials.SCOPE_GRANTED, "read");
        return values;
    }

    private void flow(Map<String, String> values, String id) {
        values.put(OAuthCredentials.FLOW_CLIENT_SOURCE, "DCR");
        values.put(OAuthCredentials.FLOW_CLIENT_ID, id);
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET, "flow-secret");
        values.put(OAuthCredentials.FLOW_CLIENT_AUTH_METHOD, "client_secret_basic");
        values.put(OAuthCredentials.FLOW_CLIENT_SCOPE, "read write");
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET_EXPIRES_AT,
                Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()));
        values.put(OAuthCredentials.CODE_VERIFIER, "verifier");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> written() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(secretService).update(eq(secret), eq(CONNECTION_ID), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("startFlow")
    class StartFlow {

        @Test
        @DisplayName("под row lock пишет state, verifier и flow-клиента, старые flow-ключи убирает")
        void writesFlowUnderLock() {
            Map<String, String> current = dcrWithGrant();
            current.put(OAuthCredentials.FLOW_CLIENT_ID, "old-flow");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            LocalDateTime expiry = LocalDateTime.now().plusMinutes(10);
            store.startFlow(CONNECTION_ID, "state-1", expiry, "verifier",
                    OAuthCredentials.flowClientKeys(
                            new ClientIdentity(OAuthClientSource.DCR, "new-flow", "flow-secret",
                                    TokenEndpointAuthMethod.CLIENT_SECRET_BASIC), "read", null));

            verify(connectionRepository).findByIdNotDeletedForUpdate(CONNECTION_ID);
            Map<String, String> written = written();
            assertEquals("state-1", connection.getOauthState());
            assertEquals(expiry, connection.getOauthStateExpiresAt());
            assertEquals("verifier", written.get(OAuthCredentials.CODE_VERIFIER));
            assertEquals("new-flow", written.get(OAuthCredentials.FLOW_CLIENT_ID));
            assertEquals("DCR", written.get(OAuthCredentials.FLOW_CLIENT_SOURCE));
            assertEquals(CLIENT_ID, written.get(OAuthCredentials.CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("storeGrant")
    class StoreGrant {

        @Test
        @DisplayName("flow-клиент переезжает в грант, старый refresh и scope не наследуются")
        void movesFlowToGrant() {
            Map<String, String> current = dcrWithGrant();
            flow(current, "flow-client");
            OAuthTokens tokens = new OAuthTokens("access-2", "refresh-2", LocalDateTime.now().plusHours(1),
                    "read write");

            store.storeGrant(connection, current, tokens);

            Map<String, String> written = written();
            assertEquals("flow-client", written.get(OAuthCredentials.CLIENT_ID));
            assertEquals("flow-secret", written.get(OAuthCredentials.CLIENT_SECRET));
            assertEquals("read write", written.get(OAuthCredentials.CLIENT_SCOPE));
            assertEquals("refresh-2", written.get(OAuthCredentials.REFRESH_TOKEN));
            assertEquals("read write", written.get(OAuthCredentials.SCOPE_GRANTED));
            assertFalse(written.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
            assertFalse(written.containsKey(OAuthCredentials.CODE_VERIFIER));
            assertEquals(ConnectionAuthStatus.AUTHORIZED, connection.getAuthStatus());
        }

        @Test
        @DisplayName("AS не выдал refresh — старый refresh и granted scope удаляются, а не наследуются")
        void doesNotInheritRefresh() {
            Map<String, String> current = dcrWithGrant();
            flow(current, "flow-client");
            OAuthTokens tokens = new OAuthTokens("access-2", null, null, null);

            store.storeGrant(connection, current, tokens);

            Map<String, String> written = written();
            assertFalse(written.containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(written.containsKey(OAuthCredentials.SCOPE_GRANTED));
        }
    }

    @Nested
    @DisplayName("storeRefreshed")
    class StoreRefreshed {

        @Test
        @DisplayName("сверяет пару client_id + refresh; verifier и flow-ключи не трогает")
        void comparesPairAndKeepsFlow() {
            Map<String, String> current = dcrWithGrant();
            flow(current, "flow-client");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);
            OAuthTokens tokens = new OAuthTokens("access-2", "refresh-2", LocalDateTime.now().plusHours(1),
                    "read");

            assertTrue(store.storeRefreshed(CONNECTION_ID, CLIENT_ID, "refresh-1", tokens));

            Map<String, String> written = written();
            assertEquals("verifier", written.get(OAuthCredentials.CODE_VERIFIER));
            assertEquals("flow-client", written.get(OAuthCredentials.FLOW_CLIENT_ID));
            assertEquals(CLIENT_ID, written.get(OAuthCredentials.CLIENT_ID));
            assertEquals("access-2", written.get(OAuthCredentials.ACCESS_TOKEN));
        }

        @Test
        @DisplayName("клиент уже другой — токены старого клиента не пишем")
        void discardsOtherClient() {
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(dcrWithGrant());

            assertFalse(store.storeRefreshed(CONNECTION_ID, "other-client", "refresh-1",
                    new OAuthTokens("access-2", "refresh-2", null, null)));

            verify(secretService, never()).update(any(), any(), anyMap());
        }

        @Test
        @DisplayName("refresh-токен другой при том же клиенте — тоже не пишем")
        void discardsOtherRefresh() {
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(dcrWithGrant());

            assertFalse(store.storeRefreshed(CONNECTION_ID, CLIENT_ID, "refresh-other",
                    new OAuthTokens("access-2", "refresh-2", null, null)));

            verify(secretService, never()).update(any(), any(), anyMap());
        }

        @Test
        @DisplayName("старый CIMD-грант без client_id сверяется только по refresh-токену")
        void oldCimdGrantWithoutClientId() {
            Map<String, String> current = dcrWithGrant();
            current.remove(OAuthCredentials.CLIENT_SOURCE);
            current.remove(OAuthCredentials.CLIENT_ID);
            current.remove(OAuthCredentials.CLIENT_SECRET);
            current.remove(OAuthCredentials.CLIENT_AUTH_METHOD);
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            assertTrue(store.storeRefreshed(CONNECTION_ID, "https://agimate.example/client.json",
                    "refresh-1", new OAuthTokens("access-2", "refresh-2", null, "read")));

            verify(secretService).update(eq(secret), eq(CONNECTION_ID), anyMap());
        }
    }

    @Nested
    @DisplayName("rejectRefresh")
    class RejectRefresh {

        @Test
        @DisplayName("стирает refresh и expiry, клиента и source оставляет")
        void keepsClient() {
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(dcrWithGrant());

            assertTrue(store.rejectRefresh(CONNECTION_ID, "refresh-1"));

            Map<String, String> written = written();
            assertFalse(written.containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertEquals(CLIENT_ID, written.get(OAuthCredentials.CLIENT_ID));
            assertEquals("DCR", written.get(OAuthCredentials.CLIENT_SOURCE));
            assertNull(connection.getOauthExpiresAt());
            assertEquals(ConnectionAuthStatus.AUTH_EXPIRED, connection.getAuthStatus());
            verify(eventPublisher).publishEvent(any(ConnectorModifiedEvent.class));
        }

        @Test
        @DisplayName("пока шёл обмен, пользователь переавторизовался — новую цепочку не трогаем")
        void keepsNewerGrant() {
            Map<String, String> current = dcrWithGrant();
            current.put(OAuthCredentials.REFRESH_TOKEN, "refresh-2");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            assertFalse(store.rejectRefresh(CONNECTION_ID, "refresh-1"));

            verify(secretService, never()).update(any(), any(), anyMap());
            verify(connectionRepository, never()).save(any());
            assertEquals(ConnectionAuthStatus.AUTHORIZED, connection.getAuthStatus());
        }
    }

    @Nested
    @DisplayName("rejectRefreshClient")
    class RejectRefreshClient {

        @Test
        @DisplayName("стирает креденшлы клиента, refresh и expiry, source сохраняет")
        void keepsSourceOnly() {
            Map<String, String> current = dcrWithGrant();
            flow(current, CLIENT_ID);
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            assertTrue(store.rejectRefreshClient(CONNECTION_ID, "refresh-1"));

            Map<String, String> written = written();
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_ID));
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_SECRET));
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_AUTH_METHOD));
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_SCOPE));
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_SECRET_EXPIRES_AT));
            assertFalse(written.containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(written.containsKey(OAuthCredentials.SCOPE_GRANTED));
            assertFalse(written.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
            assertEquals("DCR", written.get(OAuthCredentials.CLIENT_SOURCE));
            assertEquals(ConnectionAuthStatus.AUTH_EXPIRED, connection.getAuthStatus());
            verify(eventPublisher).publishEvent(any(ConnectorModifiedEvent.class));
        }

        @Test
        @DisplayName("flow-клиент новее отвергнутого — его не трогаем")
        void keepsNewerFlowClient() {
            Map<String, String> current = dcrWithGrant();
            flow(current, "newer-flow-client");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            assertTrue(store.rejectRefreshClient(CONNECTION_ID, "refresh-1"));

            Map<String, String> written = written();
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_ID));
            assertEquals("newer-flow-client", written.get(OAuthCredentials.FLOW_CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("clearRejectedClient")
    class ClearRejectedClient {

        @Test
        @DisplayName("flow-клиент совпал с грантом — чистит грант, source остаётся")
        void clearsGrantWhenSameClient() {
            Map<String, String> current = dcrWithGrant();
            flow(current, CLIENT_ID);
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            store.clearRejectedClient(CONNECTION_ID);

            Map<String, String> written = written();
            assertFalse(written.containsKey(OAuthCredentials.CLIENT_ID));
            assertFalse(written.containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(written.containsKey(OAuthCredentials.SCOPE_GRANTED));
            assertFalse(written.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
            assertEquals("DCR", written.get(OAuthCredentials.CLIENT_SOURCE));
            assertEquals(ConnectionAuthStatus.AUTHORIZED, connection.getAuthStatus());
        }

        @Test
        @DisplayName("flow-клиент новый — грант не трогает, чистит только flow")
        void keepsGrantForNewFlowClient() {
            Map<String, String> current = dcrWithGrant();
            flow(current, "another-client");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            store.clearRejectedClient(CONNECTION_ID);

            Map<String, String> written = written();
            assertEquals(CLIENT_ID, written.get(OAuthCredentials.CLIENT_ID));
            assertEquals("refresh-1", written.get(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(written.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
        }

        @Test
        @DisplayName("CIMD-флоу — чистит только flow: идентичность приходит из конфига")
        void cimdClearsFlowOnly() {
            Map<String, String> current = dcrWithGrant();
            current.put(OAuthCredentials.FLOW_CLIENT_SOURCE, "CIMD");
            current.put(OAuthCredentials.FLOW_CLIENT_ID, CLIENT_ID);
            current.put(OAuthCredentials.FLOW_CLIENT_AUTH_METHOD, "none");
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            store.clearRejectedClient(CONNECTION_ID);

            Map<String, String> written = written();
            assertEquals(CLIENT_ID, written.get(OAuthCredentials.CLIENT_ID));
            assertEquals("refresh-1", written.get(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(written.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
        }

        @Test
        @DisplayName("invalid_scope убирает offline_access из следующего запроса")
        void dropsOfflineAccessOnScopeRejection() {
            Map<String, String> current = dcrWithGrant();
            current.put(OAuthCredentials.SCOPE_REQUESTED, "read offline_access");
            flow(current, CLIENT_ID);
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(current);

            store.clearRejectedClient(CONNECTION_ID, true);

            assertEquals("read", written().get(OAuthCredentials.SCOPE_REQUESTED));
        }

        @Test
        @DisplayName("коннекции уже нет — молча выходим")
        void missingConnectionIsNoOp() {
            when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.empty());

            store.clearRejectedClient(CONNECTION_ID);

            verify(secretService, never()).update(any(), any(), anyMap());
        }

        @Test
        @DisplayName("у коннекции нет секрета — молча выходим")
        void missingSecretIsNoOp() {
            connection.setSecretId(null);

            store.clearRejectedClient(CONNECTION_ID);

            verify(secretService, never()).update(any(), any(), anyMap());
        }
    }

    @Nested
    @DisplayName("expireWithoutClient")
    class ExpireWithoutClient {

        @Test
        @DisplayName("гасит expiry, ставит AUTH_EXPIRED и публикует событие для джобы")
        void expires() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(Map.of());

            store.expireWithoutClient(CONNECTION_ID);

            assertNull(connection.getOauthExpiresAt());
            assertEquals(ConnectionAuthStatus.AUTH_EXPIRED, connection.getAuthStatus());
            verify(eventPublisher).publishEvent(any(ConnectorModifiedEvent.class));
        }

        @Test
        @DisplayName("пока проверяли, появился рабочий клиент — грант не гасим")
        void keepsUsableClient() {
            LocalDateTime expiry = LocalDateTime.now().plusMinutes(1);
            connection.setOauthExpiresAt(expiry);
            when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(dcrWithGrant());

            store.expireWithoutClient(CONNECTION_ID);

            assertEquals(expiry, connection.getOauthExpiresAt());
            assertEquals(ConnectionAuthStatus.AUTHORIZED, connection.getAuthStatus());
            verify(eventPublisher, never()).publishEvent(any(ConnectorModifiedEvent.class));
        }

        @Test
        @DisplayName("нечитаемый секрет — тоже повод погасить грант, а не тикать с ошибкой")
        void unreadableSecretExpires() {
            connection.setOauthExpiresAt(LocalDateTime.now().plusMinutes(1));
            when(secretService.reveal(secret, CONNECTION_ID))
                    .thenThrow(new IllegalStateException("bad KEK"));

            store.expireWithoutClient(CONNECTION_ID);

            assertNull(connection.getOauthExpiresAt());
            assertEquals(ConnectionAuthStatus.AUTH_EXPIRED, connection.getAuthStatus());
            verify(eventPublisher).publishEvent(any(ConnectorModifiedEvent.class));
        }
    }
}
