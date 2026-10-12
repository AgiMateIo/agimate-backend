package ru.agimate.controlapi.controller.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.controlapi.config.InboundRateLimitProperties;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthEndpoints;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthTokens;
import ru.agimate.controlapi.service.mcp.oauth.McpTokenService;
import ru.agimate.controlapi.service.mcp.oauth.TokenResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("McpOAuthController: метаданные, токен-эндпойнт и отзыв")
class McpOAuthControllerTest {

    private static final String CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";

    private final McpTokenService tokenService = mock(McpTokenService.class);
    private McpOAuthController controller;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties();
        properties.setPublicUrl("https://api.agimate.io/control");
        controller = new McpOAuthController(new McpOAuthEndpoints(properties, "https://agimate.io"),
                tokenService, new InboundRateLimiter(new InboundRateLimitProperties()));
        when(tokenService.exchangeCode(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResult.Issued("agma-access", "agmr-refresh", 3600));
        when(tokenService.refresh(any(), any()))
                .thenReturn(new TokenResult.Refused("invalid_grant", "Unknown refresh token"));
    }

    /** As the ingress forwards it: the context path in front, the suffix the client derived kept. */
    private static MockHttpServletRequest get(String pathWithinApplication) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/control" + pathWithinApplication);
        request.setContextPath("/control");
        return request;
    }

    private static MultiValueMap<String, String> form(String... pairs) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    private static String wellFormed(String prefix) {
        return AppKeyUtils.generate(prefix).fullKey();
    }

    @Nested
    @DisplayName("метаданные")
    class Metadata {

        @Test
        @DisplayName("PRM: голый адрес и с суффиксом ресурса — 200, чужой суффикс — 404")
        void protectedResourceSuffix() {
            assertEquals(200, controller.protectedResourceMetadata(get("/.well-known/oauth-protected-resource")).getStatusCode().value());
            assertEquals(200, controller.protectedResourceMetadata(get("/.well-known/oauth-protected-resource/control/mcp")).getStatusCode().value());
            assertEquals(404, controller.protectedResourceMetadata(get("/.well-known/oauth-protected-resource/other")).getStatusCode().value());
        }

        @Test
        @DisplayName("метаданные AS: суффикс issuer'а — 200, адрес ресурса в PRM — /mcp под публичной базой")
        void authorizationServerSuffix() {
            assertEquals(200, controller.authorizationServerMetadata(get("/.well-known/oauth-authorization-server/control")).getStatusCode().value());
            assertEquals(404, controller.authorizationServerMetadata(get("/.well-known/oauth-authorization-server/user")).getStatusCode().value());
            assertEquals("https://api.agimate.io/control/mcp",
                    controller.protectedResourceMetadata(get("/.well-known/oauth-protected-resource")).getBody().get("resource"));
        }

        @Test
        @DisplayName("openid-configuration отдаёт те же метаданные, что RFC 8414")
        void openidConfigurationMirrorsAuthorizationServer() {
            assertEquals(controller.authorizationServerMetadata(get("/.well-known/oauth-authorization-server")).getBody(),
                    controller.openidConfiguration().getBody());
        }
    }

    @Nested
    @DisplayName("токен-эндпойнт")
    class Token {

        @Test
        @DisplayName("повторённый параметр — invalid_request, неизвестный grant_type — unsupported_grant_type")
        void requestShape() {
            assertEquals("invalid_request",
                    controller.token(form("grant_type", "authorization_code", "code", "a", "code", "b")).getBody().get("error"));
            assertEquals("unsupported_grant_type", controller.token(form("grant_type", "password")).getBody().get("error"));
            assertEquals("unsupported_grant_type", controller.token(form()).getBody().get("error"));
            verifyNoInteractions(tokenService);
        }

        @Test
        @DisplayName("выдача — 200, тело OAuth и no-store: токены не должны осесть в кэше")
        void issuedResponse() {
            String code = wellFormed(McpOAuthTokens.CODE_PREFIX);
            ResponseEntity<Map<String, Object>> response = controller.token(form("grant_type", "authorization_code",
                    "code", code, "code_verifier", "v", "client_id", CLAUDE, "redirect_uri", "https://claude.ai/cb"));

            assertEquals(200, response.getStatusCode().value());
            assertEquals(Map.of("access_token", "agma-access", "token_type", "Bearer", "expires_in", 3600L,
                    "refresh_token", "agmr-refresh"), response.getBody());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals("no-cache", response.getHeaders().getFirst("Pragma"));
            verify(tokenService).exchangeCode(code, "v", CLAUDE, "https://claude.ai/cb", null);
        }

        @Test
        @DisplayName("refresh уходит в refresh, отказ — 400 с error и no-store")
        void refusedResponse() {
            String refresh = wellFormed(McpOAuthTokens.REFRESH_PREFIX);
            ResponseEntity<Map<String, Object>> response = controller.token(
                    form("grant_type", "refresh_token", "refresh_token", refresh, "client_id", CLAUDE));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("invalid_grant", response.getBody().get("error"));
            assertEquals("no-store", response.getHeaders().getCacheControl());
            verify(tokenService).refresh(refresh, CLAUDE);
        }

        @Test
        @DisplayName("лимит — на предъявленный токен: одиннадцатый refresh им же получает 429, соседний токен — нет")
        void limitedPerCredential() {
            String hammered = wellFormed(McpOAuthTokens.REFRESH_PREFIX);
            for (int i = 0; i < 10; i++) {
                assertEquals(400, controller.token(form("grant_type", "refresh_token", "refresh_token", hammered))
                        .getStatusCode().value());
            }
            ResponseEntity<Map<String, Object>> limited =
                    controller.token(form("grant_type", "refresh_token", "refresh_token", hammered));
            assertEquals(429, limited.getStatusCode().value());
            assertEquals("temporarily_unavailable", limited.getBody().get("error"));

            assertEquals(400, controller.token(form("grant_type", "refresh_token",
                    "refresh_token", wellFormed(McpOAuthTokens.REFRESH_PREFIX))).getStatusCode().value());
        }

        @Test
        @DisplayName("чужой поток с тем же client_id не съедает лимит честных пользователей клиента")
        void sharedClientIdIsNotABucket() {
            for (int i = 0; i < 100; i++) {
                controller.token(form("grant_type", "refresh_token",
                        "refresh_token", wellFormed(McpOAuthTokens.REFRESH_PREFIX), "client_id", CLAUDE));
            }
            assertEquals(400, controller.token(form("grant_type", "refresh_token",
                    "refresh_token", wellFormed(McpOAuthTokens.REFRESH_PREFIX), "client_id", CLAUDE)).getStatusCode().value());
        }

        @Test
        @DisplayName("кривой токен корзины не получает: он отвергается без похода в базу")
        void malformedCredentialIsNotLimited() {
            for (int i = 0; i < 20; i++) {
                assertEquals(400, controller.token(form("grant_type", "refresh_token", "refresh_token", "agmr-garbage"))
                        .getStatusCode().value());
            }
        }
    }

    @Nested
    @DisplayName("отзыв")
    class Revoke {

        @Test
        @DisplayName("неизвестный токен — 200, client_id передаётся сервису")
        void unknownTokenIsOk() {
            String token = wellFormed(McpOAuthTokens.ACCESS_PREFIX);
            assertEquals(200, controller.revoke(form("token", token, "client_id", CLAUDE)).getStatusCode().value());
            verify(tokenService).revoke(token, CLAUDE);
        }

        @Test
        @DisplayName("без токена — 200 и сервис не зовётся; повторённый параметр — invalid_request")
        void shape() {
            assertEquals(200, controller.revoke(form()).getStatusCode().value());
            // any(), not anyString(): the absent token is null, and anyString() never matches null.
            verify(tokenService, never()).revoke(any(), any());

            ResponseEntity<Map<String, Object>> repeated = controller.revoke(form("token", "a", "token", "b"));
            assertEquals(400, repeated.getStatusCode().value());
            assertEquals("invalid_request", repeated.getBody().get("error"));
        }

        @Test
        @DisplayName("лимит тот же, что у токен-эндпойнта: на предъявленный токен")
        void limitedPerCredential() {
            String token = wellFormed(McpOAuthTokens.REFRESH_PREFIX);
            for (int i = 0; i < 10; i++) {
                controller.revoke(form("token", token));
            }
            assertEquals(429, controller.revoke(form("token", token)).getStatusCode().value());
        }
    }
}
