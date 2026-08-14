package ru.agimate.controlapi.acp;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import ru.agimate.controlapi.security.AgentPrincipal;

import java.util.Map;

/**
 * Carries the {@link AgentPrincipal} from the handshake request's SecurityContext (set there by
 * {@code AgentAuthFilter} from the {@code X-Api-Key} — the {@code /acp} path is part of the api-key
 * chain) into the WebSocket session's attributes: after the upgrade the SecurityContext is out of
 * reach.
 */
public class AcpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "acpPrincipal";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AgentPrincipal principal) {
            attributes.put(ATTR_PRINCIPAL, principal);
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
