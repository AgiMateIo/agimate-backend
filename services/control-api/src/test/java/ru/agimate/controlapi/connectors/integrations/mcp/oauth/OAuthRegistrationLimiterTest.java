package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("OAuthRegistrationLimiter")
class OAuthRegistrationLimiterTest {

    private static final String ENDPOINT = "https://auth.example.com/register";

    private McpOAuthProperties properties;
    private SimpleMeterRegistry metrics;
    private OAuthRegistrationLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new McpOAuthProperties();
        metrics = new SimpleMeterRegistry();
        limiter = new OAuthRegistrationLimiter(properties, metrics);
    }

    @Test
    @DisplayName("лимит пользователя исчерпан — повторный старт регистрации отказывает")
    void perUser() {
        properties.setRegistrationsPerUserPerHour(1);
        UUID user = UUID.randomUUID();

        limiter.check(user, ENDPOINT);

        assertThrows(ConnectorException.class, () -> limiter.check(user, ENDPOINT));
        assertEquals(1.0, metrics.counter("oauth_client_registration",
                "scope", "user", "result", "refused").count());
    }

    @Test
    @DisplayName("лимит сервера общий для пользователей")
    void perServer() {
        properties.setRegistrationsPerUserPerHour(100);
        properties.setRegistrationsPerServerPerHour(1);

        limiter.check(UUID.randomUUID(), ENDPOINT);

        assertThrows(ConnectorException.class,
                () -> limiter.check(UUID.randomUUID(), ENDPOINT));
        assertEquals(1.0, metrics.counter("oauth_client_registration",
                "scope", "server", "result", "refused").count());
    }

    @Test
    @DisplayName("0 отключает соответствующий лимит")
    void disabled() {
        properties.setRegistrationsPerUserPerHour(0);
        properties.setRegistrationsPerServerPerHour(0);
        UUID user = UUID.randomUUID();

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                limiter.check(user, ENDPOINT);
            }
        });
    }
}
