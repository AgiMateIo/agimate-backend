package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.McpUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A per-instance ceiling on new DCR registrations, checked before the network. The ordinary reconnect
 * reuses the grant's client and never arrives here; a burst comes from a wider scope, a dead client or
 * somebody poking the endpoint. An authorisation server's own limit is a backstop, not a plan — its
 * block is by IP and would hit the whole installation.
 *
 * <p>In memory on purpose: a registration is a rare, user-initiated action, and the state may be lost
 * on restart without harm. A refusal blocks the key for a short cooldown, which is the backoff; the
 * counter carries the outcome for whoever watches the metric.
 */
@Slf4j
@Component
public class OAuthRegistrationLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration COOLDOWN = Duration.ofMinutes(5);
    private static final long WINDOW_MILLIS = WINDOW.toMillis();
    private static final long COOLDOWN_MILLIS = COOLDOWN.toMillis();

    private final McpOAuthProperties properties;
    private final MeterRegistry metrics;
    private final LongSupplier clock;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    @Autowired
    public OAuthRegistrationLimiter(McpOAuthProperties properties, MeterRegistry metrics) {
        this(properties, metrics, System::currentTimeMillis);
    }

    OAuthRegistrationLimiter(McpOAuthProperties properties, MeterRegistry metrics, LongSupplier clock) {
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @throws ConnectorException when the user or the server has exhausted its window */
    public void check(UUID userId, String registrationEndpoint) {
        if (properties.getRegistrationsPerUserPerHour() > 0) {
            acquire("user:" + userId, properties.getRegistrationsPerUserPerHour(), "user");
        }
        if (properties.getRegistrationsPerServerPerHour() > 0) {
            acquire("server:" + McpUtils.logTarget(registrationEndpoint),
                    properties.getRegistrationsPerServerPerHour(), "server");
        }
    }

    private void acquire(String key, int limit, String scope) {
        long now = clock.getAsLong();
        Window window = windows.get(key, ignored -> new Window(now));
        boolean allowed;
        synchronized (window) {
            allowed = window.tryAcquire(now, limit);
        }
        metrics.counter("oauth_client_registration", "scope", scope,
                "result", allowed ? "allowed" : "refused").increment();
        if (!allowed) {
            log.warn("Client registration refused for {} (limit {} per hour); cooling down", scope, limit);
            throw new ConnectorException("Too many client registrations right now; try again later");
        }
    }

    private static final class Window {
        private long start;
        private int count;
        private long blockedUntil;

        Window(long now) {
            this.start = now;
        }

        synchronized boolean tryAcquire(long now, int limit) {
            if (now < blockedUntil) {
                return false;
            }
            if (now - start >= WINDOW_MILLIS) {
                start = now;
                count = 0;
            }
            if (count >= limit) {
                blockedUntil = now + COOLDOWN_MILLIS;
                return false;
            }
            count++;
            return true;
        }
    }
}
