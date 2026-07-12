package ru.agimate.controlapi.acp;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import ru.agimate.controlapi.security.AgentPrincipal;

import java.util.Map;

/**
 * Переносит {@link AgentPrincipal} из SecurityContext handshake-запроса (его установил
 * {@code AgentAuthFilter} по {@code X-Api-Key} — путь {@code /acp} включён в api-key chain)
 * в атрибуты WebSocket-сессии: после апгрейда SecurityContext недоступен.
 */
public class AcpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "acpPrincipal";

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AgentPrincipal principal) {
            attributes.put(ATTR_PRINCIPAL, principal);
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
