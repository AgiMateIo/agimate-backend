package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthClient")
class McpOAuthClientTest {

    private static final String TOKEN_ENDPOINT = "https://auth.example.com/token";
    private static final String REDIRECT = "https://app.example/oauth/callback";

    @Mock
    private OAuthHttpClient http;

    private McpOAuthProperties properties;
    private McpOAuthClient client;

    @BeforeEach
    void setUp() {
        properties = new McpOAuthProperties();
        properties.setRedirectUri(REDIRECT);
        client = new McpOAuthClient(http, properties);
    }

    private OAuthSetup setup() {
        return new OAuthSetup(
                "https://auth.example.com",
                "https://auth.example.com/authorize",
                TOKEN_ENDPOINT,
                "https://mcp.example.com",
                "read",
                new ClientIdentification(OAuthClientSource.DCR, "https://auth.example.com/register",
                        List.of("client_secret_basic", "client_secret_post", "none")));
    }

    private ClientIdentity dcr(String secret, TokenEndpointAuthMethod method) {
        return new ClientIdentity(OAuthClientSource.DCR, "client-1", secret, method);
    }

    private OAuthHttpClient.TokenResponse success() {
        return new OAuthHttpClient.TokenResponse(200,
                JsonUtils.toJsonNodeOrNull("""
                        {"access_token": "access-1", "refresh_token": "refresh-1", "expires_in": 3600}
                        """));
    }

    private void responds(OAuthHttpClient.TokenResponse response) {
        when(http.postForm(eq(TOKEN_ENDPOINT), any(), any())).thenReturn(response);
    }

    @Nested
    @DisplayName("authorizationUrl")
    class AuthorizationUrl {

        @Test
        @DisplayName("client_id потока, redirect, state, PKCE, scope и resource")
        void buildsUrl() {
            String url = client.authorizationUrl(setup(),
                    new ClientIdentity(OAuthClientSource.DCR, "client-1", "secret-1",
                            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    "state-1", "verifier");

            assertTrue(url.contains("client_id=client-1"));
            assertTrue(url.contains("state=state-1"));
            assertTrue(url.contains("code_challenge_method=S256"));
            assertTrue(url.contains("scope=read"));
            assertTrue(url.contains("resource="));
        }

        @Test
        @DisplayName("redirect-uri не настроен — отказ")
        void noRedirect() {
            properties.setRedirectUri("");

            assertThrows(ConnectorException.class, () -> client.authorizationUrl(setup(),
                    new ClientIdentity(OAuthClientSource.CIMD, "client-1", null,
                            TokenEndpointAuthMethod.NONE), "state", "verifier"));
        }
    }

    @Nested
    @DisplayName("аутентификация на token endpoint")
    class TokenEndpoint {

        @Test
        @DisplayName("basic: заголовок Authorization, client_id в теле не дублируется")
        void basic() {
            responds(success());

            client.exchangeCode(setup(), dcr("s:cret", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC),
                    "code", "verifier");

            ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
            verify(http).postForm(eq(TOKEN_ENDPOINT), form.capture(), headers.capture());
            assertFalse(form.getValue().containsKey("client_id"));
            assertFalse(form.getValue().containsKey("client_secret"));
            String decoded = new String(Base64.getDecoder().decode(
                    headers.getValue().get("Authorization").substring("Basic ".length())),
                    StandardCharsets.UTF_8);
            assertEquals("client-1:s%3Acret", decoded);
        }

        @Test
        @DisplayName("post: client_id и секрет в теле, заголовка нет")
        void post() {
            responds(success());

            client.exchangeCode(setup(), dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_POST),
                    "code", "verifier");

            ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
            verify(http).postForm(eq(TOKEN_ENDPOINT), form.capture(), headers.capture());
            assertEquals("client-1", form.getValue().get("client_id"));
            assertEquals("secret-1", form.getValue().get("client_secret"));
            assertFalse(headers.getValue().containsKey("Authorization"));
        }

        @Test
        @DisplayName("none: только client_id")
        void none() {
            responds(success());

            client.exchangeCode(setup(),
                    new ClientIdentity(OAuthClientSource.CIMD, "client-1", null,
                            TokenEndpointAuthMethod.NONE),
                    "code", "verifier");

            ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
            verify(http).postForm(eq(TOKEN_ENDPOINT), form.capture(), headers.capture());
            assertEquals("client-1", form.getValue().get("client_id"));
            assertFalse(form.getValue().containsKey("client_secret"));
            assertFalse(headers.getValue().containsKey("Authorization"));
        }

        @Test
        @DisplayName("401 при basic без тела — invalid_client")
        void unauthorizedBasic() {
            responds(new OAuthHttpClient.TokenResponse(401, null));

            assertThrows(OAuthClientAuthenticationException.class,
                    () -> client.exchangeCode(setup(),
                            dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC), "code", "verifier"));
        }

        @Test
        @DisplayName("401 при post без тела — тоже invalid_client")
        void unauthorizedPost() {
            responds(new OAuthHttpClient.TokenResponse(401, null));

            assertThrows(OAuthClientAuthenticationException.class,
                    () -> client.exchangeCode(setup(),
                            dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_POST), "code", "verifier"));
        }

        @Test
        @DisplayName("401 при none без тела — не invalid_client: тело решает")
        void unauthorizedNone() {
            responds(new OAuthHttpClient.TokenResponse(401, null));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> client.exchangeCode(setup(),
                            new ClientIdentity(OAuthClientSource.CIMD, "client-1", null,
                                    TokenEndpointAuthMethod.NONE), "code", "verifier"));
            assertFalse(e instanceof OAuthClientAuthenticationException);
        }

        @Test
        @DisplayName("invalid_client в теле — отдельный тип, даже при 400")
        void invalidClientBody() {
            responds(new OAuthHttpClient.TokenResponse(400,
                    JsonUtils.toJsonNodeOrNull("{\"error\": \"invalid_client\"}")));

            assertThrows(OAuthClientAuthenticationException.class,
                    () -> client.exchangeCode(setup(),
                            dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC), "code", "verifier"));
        }

        @Test
        @DisplayName("invalid_grant — отдельный тип")
        void invalidGrant() {
            responds(new OAuthHttpClient.TokenResponse(400,
                    JsonUtils.toJsonNodeOrNull("{\"error\": \"invalid_grant\"}")));

            assertThrows(OAuthGrantRejectedException.class,
                    () -> client.exchangeCode(setup(),
                            dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC), "code", "verifier"));
        }

        @Test
        @DisplayName("invalid_scope — отдельный тип")
        void invalidScope() {
            responds(new OAuthHttpClient.TokenResponse(400,
                    JsonUtils.toJsonNodeOrNull("{\"error\": \"invalid_scope\"}")));

            assertThrows(OAuthScopeRejectedException.class,
                    () -> client.exchangeCode(setup(),
                            dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC), "code", "verifier"));
        }
    }

    @Nested
    @DisplayName("секрет в ClientIdentity")
    class Redaction {

        @Test
        @DisplayName("toString не показывает секрет")
        void toStringRedacts() {
            String text = dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC).toString();

            assertFalse(text.contains("secret-1"));
            assertTrue(text.contains("<redacted>"));
        }

        @Test
        @DisplayName("@JsonIgnore не даёт сериализации вынести секрет")
        void jsonRedacts() {
            String json = JsonUtils.writeValueAsString(
                    dcr("secret-1", TokenEndpointAuthMethod.CLIENT_SECRET_BASIC));

            assertFalse(json.contains("secret-1"));
        }
    }
}
