package ru.agimate.userapi.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.database.entities.PushProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushSender")
class PushSenderTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final PushMessage MESSAGE = new PushMessage(Map.of("type", "webchat_message"), null);

    @Mock private PushSubscriptionService pushSubscriptionService;

    private static PushSubscription subscription(PushProvider provider, String token) {
        PushSubscription subscription = new PushSubscription();
        subscription.setUserId(USER_ID);
        subscription.setProvider(provider);
        subscription.setToken(token);
        return subscription;
    }

    /** A stub transport: {@code delivery} is what it reports for every send. */
    private record StubTransport(PushProvider provider, boolean isConfigured, PushDelivery delivery)
            implements PushTransport {
        @Override
        public PushDelivery send(String token, PushMessage message) {
            return delivery;
        }
    }

    private PushSender sender(PushTransport... transports) {
        return new PushSender(pushSubscriptionService, List.of(transports));
    }

    @Test
    @DisplayName("мёртвый токен сносится: это единственный надёжный сигнал о снесённом приложении")
    void goneTokenIsDropped() {
        when(pushSubscriptionService.listByUser(USER_ID))
                .thenReturn(List.of(subscription(PushProvider.RUSTORE, "dead-token")));

        sender(new StubTransport(PushProvider.RUSTORE, true, PushDelivery.TOKEN_GONE))
                .sendToUser(USER_ID, MESSAGE);

        verify(pushSubscriptionService).dropDeadToken("dead-token");
    }

    /** Транспорту бывает плохо; подписка, снесённая на 5xx, унесла бы с собой живое устройство. */
    @Test
    @DisplayName("временный сбой подписку не трогает")
    void failureKeepsTheSubscription() {
        when(pushSubscriptionService.listByUser(USER_ID))
                .thenReturn(List.of(subscription(PushProvider.RUSTORE, "live-token")));

        sender(new StubTransport(PushProvider.RUSTORE, true, PushDelivery.FAILED))
                .sendToUser(USER_ID, MESSAGE);

        verify(pushSubscriptionService, never()).dropDeadToken(anyString());
    }

    @Test
    @DisplayName("токен транспорта без кред пропускается, но не удаляется")
    void unconfiguredTransportSkips() {
        when(pushSubscriptionService.listByUser(USER_ID))
                .thenReturn(List.of(subscription(PushProvider.FIREBASE, "fcm-token")));

        sender(new StubTransport(PushProvider.RUSTORE, true, PushDelivery.DELIVERED))
                .sendToUser(USER_ID, MESSAGE);

        verify(pushSubscriptionService, never()).dropDeadToken(anyString());
    }

    /**
     * Снос мёртвого токена — запись, и она умеет падать (транзакция, база). Веер при этом обязан
     * дойти до остальных устройств: они не при чём.
     */
    @Test
    @DisplayName("сбой на одном устройстве не отменяет доставку остальным")
    void oneFailingDeviceDoesNotStopTheRest() {
        when(pushSubscriptionService.listByUser(USER_ID)).thenReturn(List.of(
                subscription(PushProvider.RUSTORE, "dead-token"),
                subscription(PushProvider.RUSTORE, "live-token")));
        doThrow(new IllegalStateException("no active transaction"))
                .when(pushSubscriptionService).dropDeadToken("dead-token");

        StubTransport transport = new StubTransport(PushProvider.RUSTORE, true, PushDelivery.TOKEN_GONE);
        sender(transport).sendToUser(USER_ID, MESSAGE);

        verify(pushSubscriptionService).dropDeadToken("live-token");
    }

    @Test
    @DisplayName("без подписок в транспорт не ходим")
    void noSubscriptionsNoCalls() {
        when(pushSubscriptionService.listByUser(USER_ID)).thenReturn(List.of());

        sender().sendToUser(USER_ID, MESSAGE);

        verify(pushSubscriptionService, never()).dropDeadToken(any());
    }
}
