package ru.agimate.controlapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
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

    /**
     * Лимит одного текстового фрейма. Дефолт контейнера — 8 КБ, чего не хватает: ответы IDE на
     * server→client запросы ({@code terminal/output}, {@code fs/read_text_file} с телом файла)
     * легко больше — при превышении контейнер рвёт соединение с close 1009 «Message Too Big».
     */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    private final AcpWebSocketHandler acpWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // Клиент — не браузер (мост/IDE), Origin-заголовка нет; политика Origin неприменима.
        registry.addHandler(acpWebSocketHandler, PATH)
                .addInterceptors(new AcpHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /** Поднимает лимиты входящих фреймов контейнера (JSR-356) для крупных ответов IDE. */
    @Bean
    public ServletServerContainerFactoryBean acpServletServerContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
