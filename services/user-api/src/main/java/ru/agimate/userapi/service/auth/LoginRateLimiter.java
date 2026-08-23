package ru.agimate.userapi.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What stands between a password and a dictionary. Counted per mailbox rather than per address of
 * the caller: the production balancer replaces the client IP with its own, so an IP limit there
 * would be one limit shared by everybody.
 *
 * <p>In memory (Caffeine) rather than in the database: a failed guess must not cost a write, and a
 * counter reset by a restart is an acceptable price for that — unlike the mail throttle, which is in
 * the database precisely because it must not be resettable.
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 10;

    /**
     * Both the window the failures are counted in and how long the block lasts: the entry dies this
     * long after it was last touched, so guessing extends its own lockout.
     */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            // A ceiling against a flood of invented addresses; honest keys are in no danger of eviction.
            .maximumSize(100_000)
            // Access and not write: incrementing the counter mutates the value in place and is not a
            // cache write at all, so expireAfterWrite would keep the deadline pinned to the first
            // failure and let a run of guesses outlive its own lockout.
            .expireAfterAccess(WINDOW)
            .build();

    public boolean blocked(String email) {
        AtomicInteger count = failures.getIfPresent(key(email));
        return count != null && count.get() >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        int count = failures.get(key(email), unused -> new AtomicInteger()).incrementAndGet();
        if (count == MAX_FAILURES) {
            log.warn("too many failed sign-ins for one mailbox — blocked for {}", WINDOW);
        }
    }

    /** A successful sign-in clears the count: the failures before it were somebody mistyping. */
    public void recordSuccess(String email) {
        failures.invalidate(key(email));
    }

    public Duration window() {
        return WINDOW;
    }

    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
