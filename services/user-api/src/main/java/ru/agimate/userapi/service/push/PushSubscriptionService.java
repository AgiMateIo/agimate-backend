package ru.agimate.userapi.service.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.database.repositories.PushSubscriptionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Every device of one person — the fan-out of a notification. */
    public List<PushSubscription> listByUser(UUID userId) {
        return pushSubscriptionRepository.findByUserId(userId);
    }

    /**
     * The caller's own subscriptions, grouped by the sign-in that registered them — what the device
     * listing shows next to each session. Rows with no session behind them (tokens registered before
     * the {@code asid} claim) are left out: there is no device in the listing to show them against,
     * and they are the sweep's business anyway.
     */
    public Map<UUID, List<PushSubscription>> byAuthSession(UUID userId) {
        return listByUser(userId).stream()
                .filter(subscription -> subscription.getAuthSessionId() != null)
                .collect(Collectors.groupingBy(PushSubscription::getAuthSessionId));
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
