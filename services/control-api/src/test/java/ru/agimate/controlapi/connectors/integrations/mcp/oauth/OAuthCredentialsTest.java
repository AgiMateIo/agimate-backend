package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthCredentials")
class OAuthCredentialsTest {

    private Map<String, String> credentials() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(OAuthCredentials.ISSUER, "https://auth.example.com");
        values.put(OAuthCredentials.TOKEN_ENDPOINT, "https://auth.example.com/token");
        values.put(OAuthCredentials.AUTHORIZATION_ENDPOINT, "https://auth.example.com/authorize");
        return values;
    }

    private Map<String, String> dcrWithFlow() {
        Map<String, String> values = credentials();
        values.put(OAuthCredentials.CLIENT_SOURCE, "DCR");
        values.put(OAuthCredentials.CLIENT_ID, "grant-client");
        values.put(OAuthCredentials.CLIENT_SECRET, "grant-secret");
        values.put(OAuthCredentials.CLIENT_AUTH_METHOD, "client_secret_basic");
        values.put(OAuthCredentials.CLIENT_SCOPE, "read");
        values.put(OAuthCredentials.REFRESH_TOKEN, "old-refresh");
        values.put(OAuthCredentials.SCOPE_GRANTED, "read");
        values.put(OAuthCredentials.CODE_VERIFIER, "verifier");
        values.put(OAuthCredentials.FLOW_CLIENT_SOURCE, "DCR");
        values.put(OAuthCredentials.FLOW_CLIENT_ID, "flow-client");
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET, "flow-secret");
        values.put(OAuthCredentials.FLOW_CLIENT_AUTH_METHOD, "client_secret_post");
        values.put(OAuthCredentials.FLOW_CLIENT_SCOPE, "read write");
        values.put(OAuthCredentials.FLOW_CLIENT_SECRET_EXPIRES_AT, "1234");
        return values;
    }

    @Nested
    @DisplayName("setup")
    class Setup {

        @Test
        @DisplayName("старый секрет без source читается как CIMD")
        void oldSecretIsCimd() {
            OAuthSetup setup = OAuthCredentials.setup(credentials());

            assertEquals(OAuthClientSource.CIMD, setup.client().source());
            assertNull(setup.client().registrationEndpoint());
        }

        @Test
        @DisplayName("DCR setup хранит endpoint и методы; повторное чтение их возвращает")
        void roundTrip() {
            OAuthSetup original = new OAuthSetup(
                    "https://auth.example.com", "https://auth.example.com/authorize",
                    "https://auth.example.com/token", "https://mcp.example.com", "read offline_access",
                    new ClientIdentification(OAuthClientSource.DCR, "https://auth.example.com/register",
                            List.of("none", "client_secret_basic")));

            OAuthSetup read = OAuthCredentials.setup(OAuthCredentials.of(original));

            assertEquals(OAuthClientSource.DCR, read.client().source());
            assertEquals("https://auth.example.com/register", read.client().registrationEndpoint());
            assertEquals(List.of("none", "client_secret_basic"),
                    read.client().tokenEndpointAuthMethods());
        }
    }

    @Nested
    @DisplayName("withGrant")
    class WithGrant {

        @Test
        @DisplayName("переносит flow-клиента в грант и удаляет старые refresh/scope/verifier")
        void movesFlow() {
            Map<String, String> updated = OAuthCredentials.withGrant(dcrWithFlow(),
                    new OAuthTokens("access-2", null, LocalDateTime.now().plusHours(1), null));

            assertEquals("flow-client", updated.get(OAuthCredentials.CLIENT_ID));
            assertEquals("flow-secret", updated.get(OAuthCredentials.CLIENT_SECRET));
            assertEquals("client_secret_post", updated.get(OAuthCredentials.CLIENT_AUTH_METHOD));
            assertEquals("read write", updated.get(OAuthCredentials.CLIENT_SCOPE));
            assertEquals("1234", updated.get(OAuthCredentials.CLIENT_SECRET_EXPIRES_AT));
            assertEquals("access-2", updated.get(OAuthCredentials.ACCESS_TOKEN));
            assertFalse(updated.containsKey(OAuthCredentials.REFRESH_TOKEN));
            assertFalse(updated.containsKey(OAuthCredentials.SCOPE_GRANTED));
            assertFalse(updated.containsKey(OAuthCredentials.FLOW_CLIENT_ID));
            assertFalse(updated.containsKey(OAuthCredentials.CODE_VERIFIER));
        }

        @Test
        @DisplayName("новый refresh и scope ставятся из ответа")
        void storesNewTokens() {
            Map<String, String> updated = OAuthCredentials.withGrant(dcrWithFlow(),
                    new OAuthTokens("access-2", "refresh-2", LocalDateTime.now(), "read write"));

            assertEquals("refresh-2", updated.get(OAuthCredentials.REFRESH_TOKEN));
            assertEquals("read write", updated.get(OAuthCredentials.SCOPE_GRANTED));
        }
    }

    @Nested
    @DisplayName("withRefreshedTokens")
    class WithRefreshedTokens {

        @Test
        @DisplayName("сохраняет verifier и flow-клиента; refresh без ротации остаётся старым")
        void keepsFlow() {
            Map<String, String> updated = OAuthCredentials.withRefreshedTokens(dcrWithFlow(),
                    new OAuthTokens("access-2", null, LocalDateTime.now(), null));

            assertEquals("verifier", updated.get(OAuthCredentials.CODE_VERIFIER));
            assertEquals("flow-client", updated.get(OAuthCredentials.FLOW_CLIENT_ID));
            assertEquals("old-refresh", updated.get(OAuthCredentials.REFRESH_TOKEN));
            assertEquals("access-2", updated.get(OAuthCredentials.ACCESS_TOKEN));
        }

        @Test
        @DisplayName("ротированный refresh заменяет старый")
        void rotates() {
            Map<String, String> updated = OAuthCredentials.withRefreshedTokens(dcrWithFlow(),
                    new OAuthTokens("access-2", "refresh-2", LocalDateTime.now(), "read"));

            assertEquals("refresh-2", updated.get(OAuthCredentials.REFRESH_TOKEN));
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("offline_access не уходит в регистрацию")
        void registrationScope() {
            assertEquals("read write", OAuthCredentials.registrationScope("read write offline_access"));
            assertNull(OAuthCredentials.registrationScope("offline_access"));
        }

        @Test
        @DisplayName("dropOfflineAccess убирает только offline_access, остальное сохраняет")
        void dropOffline() {
            Map<String, String> credentials = credentials();
            credentials.put(OAuthCredentials.SCOPE_REQUESTED, "read offline_access");

            OAuthCredentials.dropOfflineAccess(credentials);

            assertEquals("read", credentials.get(OAuthCredentials.SCOPE_REQUESTED));
        }

        @Test
        @DisplayName("пустой зарегистрированный scope покрывает только пустой запрос")
        void emptyRegistered() {
            assertTrue(OAuthCredentials.scopeCovers(null, ""));
            assertFalse(OAuthCredentials.scopeCovers("", "read offline_access"));
            assertTrue(OAuthCredentials.scopeCovers("read", "read offline_access"));
            assertFalse(OAuthCredentials.scopeCovers("read", "read write"));
        }
    }

    @Nested
    @DisplayName("очистка клиента")
    class Clearing {

        @Test
        @DisplayName("clearGrantClient сохраняет oauth_client_source")
        void keepsSource() {
            Map<String, String> updated = new LinkedHashMap<>(dcrWithFlow());

            OAuthCredentials.clearGrantClient(updated);

            assertNull(updated.get(OAuthCredentials.CLIENT_ID));
            assertEquals("DCR", updated.get(OAuthCredentials.CLIENT_SOURCE));
        }
    }
}
