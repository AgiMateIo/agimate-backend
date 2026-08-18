package ru.agimate.userapi.service.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.repositories.PushSubscriptionRepository;

import java.time.LocalDateTime;

/**
 * The backstop for subscriptions no session takes with it: the ones registered with a token issued
 * before the {@code asid} claim shipped, and the ones whose device simply stopped coming back. The
 * rest are removed by the revocation itself or by the cascade behind the expired session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushSubscriptionCleanupTask {

    /**
     * Equal to {@code jwt.nativeRefreshExpiration}: the application confirms its token on every
     * sign-in, so a device unseen for a whole refresh lifetime has no live session left either.
     */
    private static final int RETENTION_DAYS = 60;

    private final PushSubscriptionRepository pushSubscriptionRepository;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void purgeStale() {
        int deleted = pushSubscriptionRepository.deleteByLastSeenAtBefore(
                LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (deleted > 0) {
            log.info("purged {} stale push subscription(s)", deleted);
        }
    }
}
