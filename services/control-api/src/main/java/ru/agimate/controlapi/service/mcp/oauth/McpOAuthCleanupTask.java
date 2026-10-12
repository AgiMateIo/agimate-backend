package ru.agimate.controlapi.service.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthTokenRepository;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Sweeps what the authorization server leaves behind: consents whose code was never exchanged,
 * expired tokens, and grants with no token left to use. Every statement is idempotent, so several
 * instances running it at once only repeat each other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpOAuthCleanupTask {

    /** Well past the code's minute: a consent still being exchanged must not vanish under it. */
    private static final Duration PENDING_GRACE = Duration.ofMinutes(10);

    private final McpOAuthGrantRepository grantRepository;
    private final McpOAuthTokenRepository tokenRepository;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int pending = grantRepository.deletePendingBefore(now.minus(PENDING_GRACE));
        // A rotated refresh token keeps answering retries for the window, so expiry alone is not the line.
        int tokens = tokenRepository.deleteExpiredBefore(now.minus(McpOAuthTokens.RETRY_WINDOW));
        int exhausted = grantRepository.revokeExhausted(now);
        if (pending + tokens + exhausted > 0) {
            log.info("MCP OAuth sweep: {} abandoned consent(s), {} expired token(s), {} exhausted grant(s)",
                    pending, tokens, exhausted);
        }
    }
}
