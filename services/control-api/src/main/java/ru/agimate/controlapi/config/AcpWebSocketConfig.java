package ru.agimate.controlapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import ru.agimate.controlapi.acp.AcpHandshakeInterceptor;
import ru.agimate.controlapi.acp.AcpWebSocketHandler;

/**
 * WebSocket-эндпоинт ACP (Agent Client Protocol) для IDE-клиентов. Путь включён в api-key
 * security chain ({@code SecurityConfig}): handshake аутентифицируется агентским
 * {@code X-Api-Key}, принципала в сессию переносит {@link AcpHandshakeInterceptor}.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AcpWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/acp";

    private final AcpWebSocketHandler acpWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // Клиент — не браузер (мост/IDE), Origin-заголовка нет; политика Origin неприменима.
        registry.addHandler(acpWebSocketHandler, PATH)
                .addInterceptors(new AcpHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
