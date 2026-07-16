package ru.agimate.controlapi.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.config.InboundRateLimitProperties;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter.Scope;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InboundRateLimiter")
class InboundRateLimiterTest {

    private InboundRateLimitProperties properties;
    private AtomicLong nowNanos;
    private InboundRateLimiter limiter;

    private final UUID connectionA = UUID.randomUUID();
    private final UUID connectionB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new InboundRateLimitProperties();
        properties.setTriggersPerMinute(60);
        properties.setToolResultsPerMinute(2);
        nowNanos = new AtomicLong(0);
        limiter = new InboundRateLimiter(properties, nowNanos::get);
    }

    private void advance(Duration duration) {
        nowNanos.addAndGet(duration.toNanos());
    }

    private int acquiredOf(int attempts, Scope scope, UUID connectionId) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(scope, connectionId)) {
                acquired++;
            }
        }
        return acquired;
    }

    @Nested
    @DisplayName("burst and refill")
    class BurstAndRefill {

        @Test
        @DisplayName("allows a full burst up to the per-minute limit, then rejects")
        void allowsBurstThenRejects() {
            assertTrue(acquiredOf(60, Scope.TRIGGER, connectionA) == 60);
            assertFalse(limiter.tryAcquire(Scope.TRIGGER, connectionA));
        }

        @Test
        @DisplayName("refills proportionally to elapsed time")
        void refillsOverTime() {
            acquiredOf(60, Scope.TRIGGER, connectionA);
            assertFalse(limiter.tryAcquire(Scope.TRIGGER, connectionA));

            advance(Duration.ofSeconds(2)); // 60/min = 1/сек → 2 токена
            assertTrue(acquiredOf(3, Scope.TRIGGER, connectionA) == 2);
        }

        @Test
        @DisplayName("does not accumulate above capacity while idle")
        void capsAtCapacity() {
            advance(Duration.ofMinutes(10));
            assertTrue(acquiredOf(61, Scope.TRIGGER, connectionA) == 60);
        }
    }

    @Nested
    @DisplayName("isolation")
    class Isolation {

        @Test
        @DisplayName("connections do not share buckets")
        void perConnection() {
            acquiredOf(60, Scope.TRIGGER, connectionA);
            assertFalse(limiter.tryAcquire(Scope.TRIGGER, connectionA));
            assertTrue(limiter.tryAcquire(Scope.TRIGGER, connectionB));
        }

        @Test
        @DisplayName("scopes have independent limits for the same connection")
        void perScope() {
            acquiredOf(2, Scope.TOOL_RESULT, connectionA);
            assertFalse(limiter.tryAcquire(Scope.TOOL_RESULT, connectionA));
            assertTrue(limiter.tryAcquire(Scope.TRIGGER, connectionA));
        }
    }

    @Nested
    @DisplayName("configuration switches")
    class ConfigurationSwitches {

        @Test
        @DisplayName("disabled limiter always allows")
        void disabledAllowsAll() {
            properties.setEnabled(false);
            assertTrue(acquiredOf(1000, Scope.TRIGGER, connectionA) == 1000);
        }

        @Test
        @DisplayName("non-positive limit disables the scope")
        void nonPositiveLimitAllowsAll() {
            properties.setTriggersPerMinute(0);
            assertTrue(acquiredOf(1000, Scope.TRIGGER, connectionA) == 1000);
        }
    }
}
