package ru.agimate.userapi.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.database.repositories.PushSubscriptionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushSubscriptionService")
class PushSubscriptionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AUTH_SESSION_ID = UUID.randomUUID();
    private static final String TOKEN = "rustore-token-0123456789";

    @Mock private PushSubscriptionRepository pushSubscriptionRepository;

    @InjectMocks private PushSubscriptionService service;

    @Test
    @DisplayName("регистрация: upsert с именем провайдера и сессией входа")
    void registerUpserts() {
        service.register(USER_ID, AUTH_SESSION_ID, PushProvider.RUSTORE, TOKEN);

        verify(pushSubscriptionRepository).upsert(
                eq(USER_ID), eq(AUTH_SESSION_ID), eq("RUSTORE"), eq(TOKEN), any(LocalDateTime.class));
    }

    /** Токен, выпущенный до появления claim'а asid, регистрируется — просто без ключа чистки. */
    @Test
    @DisplayName("регистрация без сессии входа проходит")
    void registerWithoutAuthSession() {
        service.register(USER_ID, null, PushProvider.RUSTORE, TOKEN);

        verify(pushSubscriptionRepository).upsert(
                eq(USER_ID), isNull(), eq("RUSTORE"), eq(TOKEN), any(LocalDateTime.class));
    }

    /** Токен уникален глобально, но снести чужую подписку по нему нельзя — только свою. */
    @Test
    @DisplayName("снятие ограничено владельцем и не падает, когда сносить нечего")
    void unregisterIsScopedAndIdempotent() {
        when(pushSubscriptionRepository.deleteByUserIdAndToken(USER_ID, TOKEN)).thenReturn(0);

        service.unregister(USER_ID, TOKEN);

        verify(pushSubscriptionRepository).deleteByUserIdAndToken(USER_ID, TOKEN);
    }

    /**
     * Отзыв ставит revoked_at и строку сессии не удаляет, поэтому каскад сработает только на смёте
     * истёкших — снос подписок обязан быть явным и в той же транзакции.
     */
    @Test
    @DisplayName("отзыв сессии сносит её подписки")
    void dropByAuthSession() {
        when(pushSubscriptionRepository.deleteByAuthSessionId(AUTH_SESSION_ID)).thenReturn(2);

        service.dropByAuthSession(AUTH_SESSION_ID);

        verify(pushSubscriptionRepository).deleteByAuthSessionId(AUTH_SESSION_ID);
    }

    @Nested
    @DisplayName("листинг устройств")
    class ByAuthSession {

        @Test
        @DisplayName("подписки группируются по сессии входа")
        void groupsBySession() {
            UUID otherSession = UUID.randomUUID();
            when(pushSubscriptionRepository.findByUserId(USER_ID)).thenReturn(List.of(
                    subscription(AUTH_SESSION_ID, TOKEN),
                    subscription(AUTH_SESSION_ID, TOKEN + "-rotated"),
                    subscription(otherSession, "another-device-token")));

            Map<UUID, List<PushSubscription>> bySession = service.byAuthSession(USER_ID);

            assertEquals(2, bySession.get(AUTH_SESSION_ID).size());
            assertEquals(1, bySession.get(otherSession).size());
        }

        /** Показать её не рядом с чем: устройства в листинге у неё нет. */
        @Test
        @DisplayName("подписка без сессии входа в листинг не попадает")
        void skipsSubscriptionsWithoutSession() {
            when(pushSubscriptionRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(subscription(null, TOKEN)));

            assertTrue(service.byAuthSession(USER_ID).isEmpty());
        }

        private PushSubscription subscription(UUID authSessionId, String token) {
            PushSubscription subscription = new PushSubscription();
            subscription.setUserId(USER_ID);
            subscription.setAuthSessionId(authSessionId);
            subscription.setProvider(PushProvider.RUSTORE);
            subscription.setToken(token);
            subscription.setLastSeenAt(LocalDateTime.now());
            return subscription;
        }
    }

    @Nested
    @DisplayName("PushProvider.fromCode")
    class Codes {

        @Test
        @DisplayName("SDK называет транспорты в нижнем регистре — принимаем любой")
        void caseInsensitive() {
            assertEquals(Optional.of(PushProvider.RUSTORE), PushProvider.fromCode("rustore"));
            assertEquals(Optional.of(PushProvider.HMS), PushProvider.fromCode(" HMS "));
        }

        @Test
        @DisplayName("незнакомый транспорт — пусто, а не исключение")
        void unknownIsEmpty() {
            assertTrue(PushProvider.fromCode("apns").isEmpty());
            assertTrue(PushProvider.fromCode("").isEmpty());
            assertTrue(PushProvider.fromCode(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("PushTokens")
    class Masking {

        @Test
        @DisplayName("в лог не попадает токен целиком")
        void maskedNeverLeaksTheToken() {
            String masked = PushTokens.masked(TOKEN);

            assertFalse(masked.contains(TOKEN));
            assertTrue(TOKEN.startsWith(masked.substring(0, masked.length() - 1)));
            assertEquals("…", PushTokens.masked("short"));
        }
    }
}
