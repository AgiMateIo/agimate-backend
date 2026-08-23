package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.repositories.AuthCodeRepository;
import ru.agimate.userapi.database.repositories.AuthSessionRepository;
import ru.agimate.userapi.database.repositories.AuthTokenRepository;

import java.time.LocalDateTime;

/**
 * Sweeps the two auth tables of rows that can no longer decide anything. Neither is urgent, which
 * is why this runs hourly and not on the request path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCleanupTask {

    /**
     * A one-time secret lives for minutes but its row is kept for a day: while it exists, a second exchange
     * is answered with "already used" and revokes the session it minted, and once it is gone the
     * same attempt is indistinguishable from a typo.
     */
    private static final int CODE_RETENTION_HOURS = 24;

    private final AuthCodeRepository codeRepository;
    private final AuthTokenRepository tokenRepository;
    private final AuthSessionRepository sessionRepository;

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();

        int codes = codeRepository.deleteExpired(now.minusHours(CODE_RETENTION_HOURS));
        int tokens = tokenRepository.deleteExpired(now.minusHours(CODE_RETENTION_HOURS));
        int sessions = sessionRepository.deleteExpired(now);

        if (codes > 0 || tokens > 0 || sessions > 0) {
            log.info("purged {} auth code(s), {} mail token(s) and {} expired session(s)",
                    codes, tokens, sessions);
        }
    }
}
