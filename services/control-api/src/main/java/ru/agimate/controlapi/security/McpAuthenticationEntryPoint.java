package ru.agimate.controlapi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthEndpoints;

import java.io.IOException;

/**
 * The {@code 401} of {@code /mcp}: the generic one plus {@code WWW-Authenticate} pointing at the
 * protected resource metadata, which is where an MCP client starts OAuth discovery (RFC 9728). A
 * client holding a valid agent key never sees it.
 */
@RequiredArgsConstructor
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final McpOAuthEndpoints endpoints;
    private final AuthenticationEntryPoint delegate;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, jakarta.servlet.ServletException {
        String challenge = "Bearer resource_metadata=\"" + endpoints.protectedResourceMetadataUrl() + "\"";
        if (request.getAttribute(AgentAuthFilter.INVALID_CREDENTIAL_ATTRIBUTE) != null) {
            challenge += ", error=\"invalid_token\"";
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        delegate.commence(request, response, authException);
    }
}
