package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DynamicClientRegistrar")
class DynamicClientRegistrarTest {

    private static final String REGISTRATION = "https://auth.example.com/register";
    private static final String REDIRECT = "https://app.example/oauth/callback";

    @Mock
    private OAuthHttpClient http;

    private McpOAuthProperties properties;
    private DynamicClientRegistrar registrar;

    @BeforeEach
    void setUp() {
        properties = new McpOAuthProperties();
        properties.setRedirectUri(REDIRECT);
        registrar = new DynamicClientRegistrar(http, properties);
    }

    private OAuthSetup setup(List<String> methods) {
        return new OAuthSetup(
                "https://auth.example.com",
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                "https://mcp.example.com",
                "read offline_access",
                new ClientIdentification(OAuthClientSource.DCR, REGISTRATION, methods));
    }

    private JsonNode json(String body) {
        return JsonUtils.toJsonNodeOrNull(body);
    }

    private void responds(int status, String body) {
        when(http.postJson(eq(REGISTRATION), any()))
                .thenReturn(new OAuthHttpClient.JsonResponse(status, body == null ? null : json(body)));
    }

    @Nested
    @DisplayName("запрос регистрации")
    class Request {

        @Test
        @DisplayName("web: application_type, имя, redirect, grants, basic и scope без offline_access")
        void webRequest() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1"}
                    """);

            registrar.register(setup(List.of("client_secret_basic", "none")));

            ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
            verify(http).postJson(eq(REGISTRATION), body.capture());
            JsonNode request = body.getValue();
            assertEquals("web", request.path("application_type").asText());
            assertEquals("AgiMate", request.path("client_name").asText());
            assertEquals(REDIRECT, request.path("redirect_uris").get(0).asText());
            assertEquals("authorization_code", request.path("grant_types").get(0).asText());
            assertEquals("refresh_token", request.path("grant_types").get(1).asText());
            assertEquals("code", request.path("response_types").get(0).asText());
            assertEquals("client_secret_basic", request.path("token_endpoint_auth_method").asText());
            assertEquals("read", request.path("scope").asText());
        }

        @Test
        @DisplayName("список методов не объявлен — дефолт RFC 8414, client_secret_basic")
        void defaultMethod() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1"}
                    """);

            registrar.register(setup(List.of()));

            ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
            verify(http).postJson(eq(REGISTRATION), body.capture());
            assertEquals("client_secret_basic",
                    body.getValue().path("token_endpoint_auth_method").asText());
        }

        @Test
        @DisplayName("http-loopback — native и none")
        void nativeRequest() {
            properties.setRedirectUri("http://127.0.0.1:8080/callback");
            responds(201, """
                    {"client_id": "client-1"}
                    """);

            RegisteredClient registered = registrar.register(setup(List.of("none")));

            ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
            verify(http).postJson(eq(REGISTRATION), body.capture());
            assertEquals("native", body.getValue().path("application_type").asText());
            assertEquals("none", body.getValue().path("token_endpoint_auth_method").asText());
            assertEquals(TokenEndpointAuthMethod.NONE, registered.identity().tokenEndpointAuthMethod());
        }

        @Test
        @DisplayName("http вне loopback — отказ до запроса")
        void nonLoopbackHttp() {
            properties.setRedirectUri("http://app.example/callback");

            assertThrows(ConnectorException.class, () -> registrar.register(setup(List.of("none"))));
        }

        @Test
        @DisplayName("DCR выключен на установке — отказ до запроса")
        void dcrDisabled() {
            properties.setDcrEnabled(false);

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
            assertTrue(e.getMessage().contains("dcr-enabled"));
        }

        @Test
        @DisplayName("native без none — отказ: публичный клиент иначе не собрать")
        void nativeNeedsNone() {
            properties.setRedirectUri("http://localhost:3000/callback");

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }
    }

    @Nested
    @DisplayName("разбор ответа")
    class Response {

        @Test
        @DisplayName("минимальный ответ: client_id и секрет, метод — запрошенный")
        void minimalResponse() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "redirect_uris": ["https://APP.EXAMPLE:443/oauth/callback"]}
                    """);

            RegisteredClient registered = registrar.register(setup(List.of("client_secret_basic")));

            assertEquals("client-1", registered.identity().clientId());
            assertEquals("secret-1", registered.identity().clientSecret());
            assertEquals(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
                    registered.identity().tokenEndpointAuthMethod());
            assertEquals("read", registered.registeredScope());
            assertNull(registered.secretExpiresAt());
        }

        @Test
        @DisplayName("объявленный post с секретом — используем post")
        void declaredPost() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "token_endpoint_auth_method": "client_secret_post"}
                    """);

            assertEquals(TokenEndpointAuthMethod.CLIENT_SECRET_POST,
                    registrar.register(setup(List.of("client_secret_basic")))
                            .identity().tokenEndpointAuthMethod());
        }

        @Test
        @DisplayName("публичный клиент без секрета — none")
        void publicClient() {
            responds(201, """
                    {"client_id": "client-1"}
                    """);

            assertEquals(TokenEndpointAuthMethod.NONE,
                    registrar.register(setup(List.of("none"))).identity().tokenEndpointAuthMethod());
        }

        @Test
        @DisplayName("секрет без метода не даёт confidential-клиента без секрета")
        void secretWithoutSecret() {
            responds(201, """
                    {"client_id": "client-1"}
                    """);

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }

        @Test
        @DisplayName("объявленный none при наличии секрета — несогласованный ответ")
        void noneWithSecret() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "token_endpoint_auth_method": "none"}
                    """);

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }

        @Test
        @DisplayName("запрошен none, метод не объявлен, секрет выдан — секрет не используется")
        void secretWithRequestedNone() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1"}
                    """);

            RegisteredClient registered = registrar.register(setup(List.of("none")));

            assertEquals(TokenEndpointAuthMethod.NONE, registered.identity().tokenEndpointAuthMethod());
            assertEquals("secret-1", registered.identity().clientSecret());
        }

        @Test
        @DisplayName("неизвестный метод в ответе — отказ")
        void unknownMethod() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "token_endpoint_auth_method": "private_key_jwt"}
                    """);

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }

        @Test
        @DisplayName("redirect_uris ответа сравниваются нормализованно")
        void redirectNormalized() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "redirect_uris": ["https://app.example/oauth/callback"]}
                    """);

            assertEquals("client-1",
                    registrar.register(setup(List.of("client_secret_basic"))).identity().clientId());
        }

        @Test
        @DisplayName("redirect_uris ответа без нашего адреса — отказ")
        void redirectMismatch() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1",
                     "redirect_uris": ["https://evil.example/callback"]}
                    """);

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }

        @Test
        @DisplayName("scope из ответа регистрации важнее запрошенного")
        void registeredScopeFromResponse() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1", "scope": "read"}
                    """);

            assertEquals("read",
                    registrar.register(setup(List.of("client_secret_basic"))).registeredScope());
        }

        @Test
        @DisplayName("ответ не вернул scope — остаётся запрошенный")
        void requestedScopeWhenResponseSilent() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1"}
                    """);

            assertEquals("read",
                    registrar.register(setup(List.of("client_secret_basic"))).registeredScope());
        }

        @Test
        @DisplayName("client_secret_expires_at 0 — срок неизвестен")
        void zeroExpiry() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1", "client_secret_expires_at": 0}
                    """);

            assertNull(registrar.register(setup(List.of("client_secret_basic"))).secretExpiresAt());
        }

        @Test
        @DisplayName("положительный client_secret_expires_at сохраняется как epoch seconds")
        void expiry() {
            responds(201, """
                    {"client_id": "client-1", "client_secret": "secret-1", "client_secret_expires_at": 1234}
                    """);

            assertEquals(1234L,
                    registrar.register(setup(List.of("client_secret_basic"))).secretExpiresAt());
        }

        @Test
        @DisplayName("ответ без client_id — отказ")
        void noClientId() {
            responds(201, """
                    {"client_secret": "secret-1"}
                    """);

            assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
        }
    }

    @Nested
    @DisplayName("ошибки")
    class Errors {

        @Test
        @DisplayName("4xx — постоянный отказ с кодом OAuth, без тела")
        void refused() {
            responds(400, """
                    {"error": "invalid_client_metadata"}
                    """);

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
            assertTrue(e.getMessage().contains("400"));
            assertTrue(e.getMessage().contains("invalid_client_metadata"));
        }

        @Test
        @DisplayName("5xx — транзиентный отказ")
        void serverError() {
            responds(503, null);

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> registrar.register(setup(List.of("client_secret_basic"))));
            assertTrue(e.getMessage().contains("try again"));
        }
    }
}
