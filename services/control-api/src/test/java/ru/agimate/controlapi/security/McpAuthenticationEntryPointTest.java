package ru.agimate.controlapi.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthEndpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("McpAuthenticationEntryPoint: 401 на /mcp с вызовом OAuth")
class McpAuthenticationEntryPointTest {

    private final AuthenticationEntryPoint plain = mock(AuthenticationEntryPoint.class);
    private McpAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties();
        properties.setPublicUrl("https://mcp.agimate.io");
        entryPoint = new McpAuthenticationEntryPoint(new McpOAuthEndpoints(properties, "https://agimate.io"), plain);
    }

    @Test
    @DisplayName("без учётных данных — ссылка на метаданные ресурса, без error: клиенту нечего выбрасывать")
    void anonymous() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest("POST", "/mcp"), response,
                new InsufficientAuthenticationException("no credentials"));

        assertEquals("Bearer resource_metadata=\"https://mcp.agimate.io/.well-known/oauth-protected-resource\"",
                response.getHeader("WWW-Authenticate"));
        verify(plain).commence(any(), any(), any());
    }

    @Test
    @DisplayName("отвергнутый токен — error=\"invalid_token\": клиент идёт за новым, а не повторяет тот же")
    void refusedCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setAttribute(AgentAuthFilter.INVALID_CREDENTIAL_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, new InsufficientAuthenticationException("refused"));

        assertEquals("Bearer resource_metadata=\"https://mcp.agimate.io/.well-known/oauth-protected-resource\", "
                + "error=\"invalid_token\"", response.getHeader("WWW-Authenticate"));
    }
}
