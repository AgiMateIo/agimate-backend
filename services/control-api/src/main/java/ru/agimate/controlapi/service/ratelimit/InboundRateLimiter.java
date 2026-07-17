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
 * Token-bucket лимитер входящего трафика внешних источников, ключ — {@code connectionId}
 * (для устройств {@code app.id == connection.id}, у вебхука connectionId в пути — единый субъект
 * для всех входов). In-memory (Caffeine): лимитер стоит на горячем пути, отклонённый запрос не
 * должен стоить обращения к БД. Состояние per-instance и сбрасывается рестартом — для защиты от
 * флуда это приемлемо; при переходе на реплики заменяется на распределённый счётчик за тем же API.
 *
 * <p>HTTP-семантика отказа — на границе: app-эндпойнты отвечают 429, вебхук молча дропает
 * (см. вызывающие контроллеры).
 */
@Component
public class InboundRateLimiter {

    /** Класс входящего трафика — у каждого свой лимит и своё ведро на connection. */
    public enum Scope { TRIGGER, TOOL_RESULT, FILE_UPLOAD }

    private record BucketKey(Scope scope, UUID connectionId) {}

    private final InboundRateLimitProperties properties;
    private final LongSupplier nanoTime;
    private final Cache<BucketKey, TokenBucket> buckets = Caffeine.newBuilder()
            // Потолок памяти при флуде рандомными connectionId; честным ключам вытеснение не грозит.
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

    /** true — запрос в пределах лимита; false — лимит исчерпан, запрос должен быть отклонён. */
    public boolean tryAcquire(Scope scope, UUID connectionId) {
        if (!properties.isEnabled()) {
            return true;
        }
        int perMinute = switch (scope) {
            case TRIGGER -> properties.getTriggersPerMinute();
            case TOOL_RESULT -> properties.getToolResultsPerMinute();
            case FILE_UPLOAD -> properties.getFileUploadsPerMinute();
        };
        if (perMinute <= 0) {
            return true;
        }
        TokenBucket bucket = buckets.get(new BucketKey(scope, connectionId),
                k -> new TokenBucket(perMinute, nanoTime.getAsLong()));
        return bucket.tryConsume(nanoTime.getAsLong());
    }

    /** Ведро: ёмкость = лимит за минуту (допустимый burst), пополнение равномерное. */
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
