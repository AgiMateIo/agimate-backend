package ru.agimate.userapi.service.auth;

import org.junit.jupiter.api.BeforeEach;
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
import ru.agimate.userapi.database.entities.AuthToken;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;
import ru.agimate.userapi.database.repositories.AuthTokenRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenService — одноразовые секреты из писем")
class AuthTokenServiceTest {

    private static final String TOKEN = "0123456789abcdef";
    private static final Duration TTL = Duration.ofHours(1);

    @Mock
    private AuthTokenRepository tokenRepository;

    @InjectMocks
    private AuthTokenService service;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
    }

    private AuthToken liveToken() {
        AuthToken token = new AuthToken();
        token.setId(UUID.randomUUID());
        token.setPurpose(AuthTokenPurpose.PASSWORD_RESET);
        token.setUserId(userId);
        token.setTokenHash(CryptoUtils.sha256Hex(TOKEN));
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        return token;
    }

    @Nested
    @DisplayName("выдача")
    class Issue {

        @Test
        @DisplayName("в базу едет хеш, наружу — сам токен")
        void storesHashOnly() {
            String issued = service.issue(userId, AuthTokenPurpose.PASSWORD_RESET, TTL);

            ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
            verify(tokenRepository).save(saved.capture());
            assertEquals(CryptoUtils.sha256Hex(issued), saved.getValue().getTokenHash());
            assertTrue(issued.length() >= 64, "токен должен быть длиной с 256 бит");
        }

        /** Иначе старое письмо в ящике остаётся рабочим ключом столько же, сколько новое. */
        @Test
        @DisplayName("новая выдача гасит прежние живые токены той же цели")
        void retiresPrevious() {
            service.issue(userId, AuthTokenPurpose.PASSWORD_RESET, TTL);

            verify(tokenRepository).retireLive(eq(userId), eq(AuthTokenPurpose.PASSWORD_RESET), any());
        }
    }

    @Nested
    @DisplayName("использование")
    class Consume {

        @Test
        @DisplayName("живой токен отдаёт владельца и гасится")
        void consumes() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveToken()));
            when(tokenRepository.claim(anyString(), any())).thenReturn(1);

            assertEquals(userId, service.consume(TOKEN, AuthTokenPurpose.PASSWORD_RESET));
        }

        @Test
        @DisplayName("истёкший токен не принимается")
        void expired() {
            AuthToken token = liveToken();
            token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.consume(TOKEN, AuthTokenPurpose.PASSWORD_RESET));
        }

        @Test
        @DisplayName("использованный токен не принимается второй раз")
        void alreadyUsed() {
            AuthToken token = liveToken();
            token.setUsedAt(LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.consume(TOKEN, AuthTokenPurpose.PASSWORD_RESET));
        }

        /**
         * Два перехода по одной ссылке одновременно: пароль должен быть задан один раз, поэтому
         * проигравшая гонку транзакция получает отказ, а не второй заход.
         */
        @Test
        @DisplayName("гонку двух переходов выигрывает один")
        void concurrent() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(liveToken()));
            when(tokenRepository.claim(anyString(), any())).thenReturn(0);

            assertThrows(ForbiddenStatusException.class,
                    () -> service.consume(TOKEN, AuthTokenPurpose.PASSWORD_RESET));
        }

        @Test
        @DisplayName("неизвестный токен отвергается так же, как истёкший")
        void unknown() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThrows(ForbiddenStatusException.class,
                    () -> service.consume(TOKEN, AuthTokenPurpose.PASSWORD_RESET));
        }
    }

    @Nested
    @DisplayName("частота писем")
    class Throttle {

        @Test
        @DisplayName("письмо только что ушло — второе не уходит")
        void tooSoon() {
            when(tokenRepository.countIssuedSince(eq(userId), any(), any())).thenReturn(1L);

            assertEquals(false, service.allowedToSend(userId, AuthTokenPurpose.PASSWORD_RESET,
                    Duration.ofMinutes(1), Duration.ofHours(1), 5));
        }

        @Test
        @DisplayName("за час исчерпан лимит — не уходит тоже")
        void tooMany() {
            when(tokenRepository.countIssuedSince(eq(userId), any(), any()))
                    .thenReturn(0L)
                    .thenReturn(5L);

            assertEquals(false, service.allowedToSend(userId, AuthTokenPurpose.PASSWORD_RESET,
                    Duration.ofMinutes(1), Duration.ofHours(1), 5));
        }

        @Test
        @DisplayName("тихий час и запас по лимиту — уходит")
        void allowed() {
            when(tokenRepository.countIssuedSince(eq(userId), any(), any()))
                    .thenReturn(0L)
                    .thenReturn(2L);

            assertTrue(service.allowedToSend(userId, AuthTokenPurpose.PASSWORD_RESET,
                    Duration.ofMinutes(1), Duration.ofHours(1), 5));
        }
    }
}
