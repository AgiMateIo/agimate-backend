package ru.agimate.userapi.service.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.PendingRegistration;
import ru.agimate.userapi.database.repositories.PendingRegistrationRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingRegistrationService — заявка, ждущая письма")
class PendingRegistrationServiceTest {

    private static final String EMAIL = "ivan@example.com";
    private static final String HASH = "{bcrypt}$2a$10$stored";
    private static final String TOKEN = "0123456789abcdef";

    @Mock
    private PendingRegistrationRepository registrationRepository;

    @InjectMocks
    private PendingRegistrationService service;

    private PendingRegistration live() {
        PendingRegistration pending = new PendingRegistration();
        pending.setEmail(EMAIL);
        pending.setPasswordHash(HASH);
        pending.setTokenHash(CryptoUtils.sha256Hex(TOKEN));
        pending.setExpiresAt(LocalDateTime.now().plusHours(1));
        return pending;
    }

    @Nested
    @DisplayName("выдача")
    class Issue {

        @Test
        @DisplayName("в базу едет хеш токена и уже готовый хеш пароля")
        void storesHashes() {
            String token = service.issue(EMAIL, HASH, "Иван", null);

            ArgumentCaptor<PendingRegistration> saved = ArgumentCaptor.forClass(PendingRegistration.class);
            verify(registrationRepository).save(saved.capture());
            assertEquals(CryptoUtils.sha256Hex(token), saved.getValue().getTokenHash());
            assertEquals(HASH, saved.getValue().getPasswordHash());
        }

        @Test
        @DisplayName("новая заявка гасит прежнюю на тот же адрес")
        void retiresPrevious() {
            service.issue(EMAIL, HASH, null, null);

            verify(registrationRepository).retireLive(eq(EMAIL), any());
        }
    }

    @Nested
    @DisplayName("подтверждение")
    class Consume {

        @Test
        @DisplayName("живая заявка отдаётся и гасится")
        void consumes() {
            when(registrationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(live()));
            when(registrationRepository.claim(anyString(), any())).thenReturn(1);

            assertEquals(EMAIL, service.consume(TOKEN).getEmail());
        }

        @Test
        @DisplayName("истёкшая заявка не подтверждается")
        void expired() {
            PendingRegistration pending = live();
            pending.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(registrationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(pending));

            assertThrows(ForbiddenStatusException.class, () -> service.consume(TOKEN));
        }

        /** Два перехода по одному письму должны завести один аккаунт, а не два. */
        @Test
        @DisplayName("гонку двух переходов выигрывает один")
        void concurrent() {
            when(registrationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(live()));
            when(registrationRepository.claim(anyString(), any())).thenReturn(0);

            assertThrows(ForbiddenStatusException.class, () -> service.consume(TOKEN));
        }

        @Test
        @DisplayName("неизвестный токен отвергается так же, как истёкший")
        void unknown() {
            when(registrationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThrows(ForbiddenStatusException.class, () -> service.consume(TOKEN));
        }
    }

    @Nested
    @DisplayName("частота писем")
    class Throttle {

        @Test
        @DisplayName("письмо только что ушло — второе не уходит")
        void tooSoon() {
            when(registrationRepository.countIssuedSince(eq(EMAIL), any())).thenReturn(1L);

            assertFalse(service.allowedToSend(EMAIL));
        }

        @Test
        @DisplayName("за час исчерпан лимит — не уходит тоже")
        void tooMany() {
            when(registrationRepository.countIssuedSince(eq(EMAIL), any()))
                    .thenReturn(0L)
                    .thenReturn(5L);

            assertFalse(service.allowedToSend(EMAIL));
        }

        @Test
        @DisplayName("тихий час и запас по лимиту — уходит")
        void allowed() {
            when(registrationRepository.countIssuedSince(eq(EMAIL), any()))
                    .thenReturn(0L)
                    .thenReturn(1L);

            assertTrue(service.allowedToSend(EMAIL));
        }
    }
}
