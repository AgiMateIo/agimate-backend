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
 * The ACP (Agent Client Protocol) WebSocket endpoint for IDE clients. The path is part of the
 * api-key security chain ({@code SecurityConfig}): the handshake is authenticated by the agent's
 * {@code X-Api-Key}, and {@link AcpHandshakeInterceptor} carries the principal into the session.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AcpWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/acp";

    /**
     * Limit on a single text frame. The container's default is 8 KB, which is not enough: an IDE's
     * answers to server→client requests ({@code terminal/output}, {@code fs/read_text_file} with a
     * file body) easily exceed it — and on overflow the container tears the connection down with
     * close 1009 «Message Too Big».
     */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    private final AcpWebSocketHandler acpWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // The client is not a browser (the bridge or the IDE), there is no Origin header; an Origin policy does not apply.
        registry.addHandler(acpWebSocketHandler, PATH)
                .addInterceptors(new AcpHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /** Raises the container's inbound frame limits (JSR-356) for large IDE responses. */
    @Bean
    public ServletServerContainerFactoryBean acpServletServerContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
