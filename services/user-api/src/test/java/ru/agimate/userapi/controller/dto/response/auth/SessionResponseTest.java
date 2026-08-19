package ru.agimate.userapi.controller.dto.response.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.controller.dto.response.push.PushSubscriptionResponse;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthSession;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.entities.PushSubscription;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SessionResponse")
class SessionResponseTest {

    private static final String TOKEN = "rustore-token-0123456789";

    /** Токен — право уведомлять устройство, и наружу он не уезжает даже своему владельцу. */
    @Test
    @DisplayName("токен подписки отдаётся только префиксом")
    void maskesThePushToken() {
        SessionResponse response = SessionResponse.of(session(), List.of(subscription()));

        PushSubscriptionResponse push = response.push().getFirst();
        assertEquals(PushProvider.RUSTORE, push.provider());
        assertFalse(push.maskedToken().contains(TOKEN));
        assertTrue(TOKEN.startsWith(push.maskedToken().substring(0, push.maskedToken().length() - 1)));
    }

    @Test
    @DisplayName("устройство без подписки отдаётся с пустым списком, а не без поля")
    void deviceWithoutSubscription() {
        assertTrue(SessionResponse.of(session(), List.of()).push().isEmpty());
    }

    private static AuthSession session() {
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID());
        session.setClient(AuthClient.NATIVE);
        session.setDeviceLabel("Pixel 8");
        session.setLastSeenAt(LocalDateTime.now());
        return session;
    }

    private static PushSubscription subscription() {
        PushSubscription subscription = new PushSubscription();
        subscription.setProvider(PushProvider.RUSTORE);
        subscription.setToken(TOKEN);
        subscription.setLastSeenAt(LocalDateTime.now());
        return subscription;
    }
}
