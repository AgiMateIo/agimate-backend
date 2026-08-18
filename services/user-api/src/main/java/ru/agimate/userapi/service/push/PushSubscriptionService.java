package ru.agimate.userapi.service.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.repositories.PushSubscriptionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The registry of devices to notify (docs/decisions/push-notifications.md). Both public operations
 * are idempotent: the application registers its token on every sign-in and on every rotation, and
 * calls the removal on every sign-out — including the ones that already happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PushSubscriptionService {

    private final PushSubscriptionRepository pushSubscriptionRepository;

    /**
     * @param authSessionId the sign-in behind the caller's token ({@code asid}); null for tokens
     *                      issued before the claim shipped — the subscription then lives until the
     *                      sweep instead of going with its session
     */
    @Transactional
    public void register(UUID userId, UUID authSessionId, PushProvider provider, String token) {
        pushSubscriptionRepository.upsert(userId, authSessionId, provider.name(), token, LocalDateTime.now());
        log.debug("push subscription {} ({}) registered for user {}, auth session {}",
                PushTokens.masked(token), provider, userId, authSessionId);
    }

    @Transactional
    public void unregister(UUID userId, String token) {
        int deleted = pushSubscriptionRepository.deleteByUserIdAndToken(userId, token);
        log.debug("push subscription {} of user {} removed: {}", PushTokens.masked(token), userId, deleted > 0);
    }

    /**
     * The sign-in has been revoked. Called inside the revoking transaction — a subscription that
     * outlived its revocation would keep a lost phone notified, and nothing later would notice.
     */
    @Transactional
    public void dropByAuthSession(UUID authSessionId) {
        int deleted = pushSubscriptionRepository.deleteByAuthSessionId(authSessionId);
        if (deleted > 0) {
            log.info("dropped {} push subscription(s) of revoked session {}", deleted, authSessionId);
        }
    }
}
