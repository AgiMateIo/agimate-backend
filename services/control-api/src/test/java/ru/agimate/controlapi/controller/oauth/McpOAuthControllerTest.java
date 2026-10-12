package ru.agimate.controlapi.controller.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.agimate.controlapi.config.InboundRateLimitProperties;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthEndpoints;
import ru.agimate.controlapi.service.mcp.oauth.McpTokenService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("McpOAuthController: метаданные и токен-эндпойнт")
class McpOAuthControllerTest {

    private McpOAuthController controller;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties();
        properties.setPublicUrl("https://api.agimate.io/control");
        controller = new McpOAuthController(new McpOAuthEndpoints(properties, "https://agimate.io"),
                mock(McpTokenService.class), new InboundRateLimiter(new InboundRateLimitProperties()));
    }

    /** As the ingress forwards it: the context path in front, the suffix the client derived kept. */
    private static MockHttpServletRequest get(String pathWithinApplication) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/control" + pathWithinApplication);
        request.setContextPath("/control");
        return request;
    }

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
    @DisplayName("повторённый параметр — invalid_request, неизвестный grant_type — unsupported_grant_type")
    void tokenRequestShape() {
        MultiValueMap<String, String> repeated = new LinkedMultiValueMap<>();
        repeated.add("grant_type", "authorization_code");
        repeated.add("code", "a");
        repeated.add("code", "b");
        Map<String, Object> body = controller.token(repeated).getBody();
        assertEquals("invalid_request", body.get("error"));

        MultiValueMap<String, String> password = new LinkedMultiValueMap<>();
        password.add("grant_type", "password");
        assertEquals("unsupported_grant_type", controller.token(password).getBody().get("error"));
    }
}
