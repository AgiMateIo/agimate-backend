package ru.agimate.controlapi.service.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.InboundRateLimitProperties;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A token-bucket limiter for inbound traffic from external sources, keyed by the subject of the entry
 * point: a connection for app and webhook traffic (for apps {@code app.id == connection.id}, for a
 * webhook the connectionId in the path), the agent for MCP calls — there the key, not a connection, is
 * what arrives. In memory (Caffeine): the limiter sits on the hot path, and a rejected request must not
 * cost a database round-trip. The state is per instance and is reset by a restart —
 * acceptable for flood protection; when replicas arrive it is replaced by a distributed counter behind
 * the same API.
 *
 * <p>The HTTP semantics of a refusal live at the boundary: the app endpoints answer 429, and the webhook
 * silently drops (see the calling controllers).
 */
@Component
public class InboundRateLimiter {

    /** A class of inbound traffic — each has its own limit and its own bucket per connection. */
    public enum Scope { TRIGGER, TOOL_RESULT, FILE_UPLOAD, MCP_CALL }

    private record BucketKey(Scope scope, UUID subjectId) {}

    private final InboundRateLimitProperties properties;
    private final LongSupplier nanoTime;
    private final Cache<BucketKey, TokenBucket> buckets = Caffeine.newBuilder()
            // A memory ceiling against a flood of random connectionIds; honest keys are in no danger of eviction.
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Autowired
    public InboundRateLimiter(InboundRateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    InboundRateLimiter(InboundRateLimitProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /** true — the request is within the limit; false — the limit is exhausted and the request must be rejected. */
    public boolean tryAcquire(Scope scope, UUID subjectId) {
        if (!properties.isEnabled()) {
            return true;
        }
        int perMinute = switch (scope) {
            case TRIGGER -> properties.getTriggersPerMinute();
            case TOOL_RESULT -> properties.getToolResultsPerMinute();
            case FILE_UPLOAD -> properties.getFileUploadsPerMinute();
            case MCP_CALL -> properties.getMcpCallsPerMinute();
        };
        if (perMinute <= 0) {
            return true;
        }
        TokenBucket bucket = buckets.get(new BucketKey(scope, subjectId),
                k -> new TokenBucket(perMinute, nanoTime.getAsLong()));
        return bucket.tryConsume(nanoTime.getAsLong());
    }

    /** The bucket: capacity = the per-minute limit (the permitted burst), refilled evenly. */
    private static final class TokenBucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int perMinute, long nowNanos) {
            this.capacity = perMinute;
            this.refillPerNano = perMinute / (double) Duration.ofMinutes(1).toNanos();
            this.tokens = perMinute;
            this.lastRefillNanos = nowNanos;
        }

        synchronized boolean tryConsume(long nowNanos) {
            tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * refillPerNano);
            lastRefillNanos = nowNanos;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
