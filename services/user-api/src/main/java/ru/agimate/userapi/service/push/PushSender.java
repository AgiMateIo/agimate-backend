package ru.agimate.userapi.service.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.repositories.PushSubscriptionRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivery of one notification to every device of one person, plus the only reliable way to learn
 * that a subscription is dead — the transport's own answer.
 *
 * <p>Nothing here throws: the caller is finishing the delivery of a message that is already written
 * and published, and a transport being unwell must not undo that.
 */
@Slf4j
@Component
public class PushSender {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final Map<PushProvider, PushTransport> transports = new EnumMap<>(PushProvider.class);

    public PushSender(PushSubscriptionRepository pushSubscriptionRepository, List<PushTransport> transports) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        transports.forEach(transport -> this.transports.put(transport.provider(), transport));
    }

    /**
     * Asynchronous, and that is part of the contract: the caller is another service reporting an
     * event, and it must not be held for as long as a transport takes to answer for every device.
     */
    @Async("pushExecutor")
    public void sendToUser(UUID userId, PushMessage message) {
        try {
            List<PushSubscription> subscriptions = pushSubscriptionRepository.findByUserId(userId);
            for (PushSubscription subscription : subscriptions) {
                deliver(subscription, message);
            }
        } catch (Exception e) {
            log.warn("push fan-out for user {} failed: {}", userId, e.toString());
        }
    }

    private void deliver(PushSubscription subscription, PushMessage message) {
        PushTransport transport = transports.get(subscription.getProvider());
        if (transport == null || !transport.isConfigured()) {
            // A token of a transport this installation cannot send through is kept, not dropped: the
            // device is fine, it is the credentials that are missing, and they may yet arrive.
            log.debug("no configured transport for {} — subscription {} skipped",
                    subscription.getProvider(), PushTokens.masked(subscription.getToken()));
            return;
        }

        PushDelivery delivery = transport.send(subscription.getToken(), message);
        if (delivery == PushDelivery.TOKEN_GONE) {
            pushSubscriptionRepository.deleteByToken(subscription.getToken());
            log.info("push subscription {} dropped: the transport no longer knows the token",
                    PushTokens.masked(subscription.getToken()));
        }
    }
}
