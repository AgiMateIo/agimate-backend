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
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthSession;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.AuthSessionRepository;
import ru.agimate.userapi.service.UserService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthSessionService — реестр сессий и ротация refresh")
class AuthSessionServiceTest {

    private static final String CURRENT_JTI = "jti-current";
    private static final String PREVIOUS_JTI = "jti-previous";
    private static final int NATIVE_REFRESH_SECONDS = 5_184_000;

    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private UserService userService;
    @Mock
    private JwtService jwtService;
    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private AuthSessionService service;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity("ivan@example.com", "Иван", "Петров", "ivan");
        user.setId(UUID.randomUUID());
    }

    private AuthSession session(AuthClient client) {
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID());
        session.setUserId(user.getId());
        session.setClient(client);
        session.setCurrentJti(CURRENT_JTI);
        session.setLastSeenAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusDays(30));
        return session;
    }

    /** Сессия, у которой уже была ротация: {@code rotatedAt} задаёт окно ретрая. */
    private AuthSession rotatedSession(LocalDateTime rotatedAt) {
        AuthSession session = session(AuthClient.NATIVE);
        session.setPreviousJti(PREVIOUS_JTI);
        session.setRotatedAt(rotatedAt);
        return session;
    }

    private void stubMinting() {
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtProperties.getNativeAccessExpiration()).thenReturn(3600);
        when(jwtProperties.getNativeRefreshExpiration()).thenReturn(NATIVE_REFRESH_SECONDS);
        when(jwtService.generateAccessToken(any(AgimateUserPrincipal.class), anyInt()))
                .thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(AgimateUserPrincipal.class), anyString(), anyInt()))
                .thenReturn("refresh-token");
    }

    @Nested
    @DisplayName("обычный рефреш")
    class Rotation {

        @Test
        @DisplayName("текущий jti меняется на новый, клиент получает пару")
        void rotatesToNewGeneration() {
            AuthSession session = session(AuthClient.NATIVE);
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));
            when(sessionRepository.rotate(eq(session.getId()), eq(CURRENT_JTI), anyString(), any(), any()))
                    .thenReturn(1);
            stubMinting();

            IssuedTokens tokens = service.refresh(CURRENT_JTI, AuthClient.NATIVE);

            assertEquals("access-token", tokens.accessToken());
            assertEquals("refresh-token", tokens.refreshToken());
            assertNotEquals(CURRENT_JTI, tokens.refreshTokenId());
            assertEquals(session.getId(), tokens.sessionId());
            verify(sessionRepository, never()).touch(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("срок жизни сессии отодвигается — она умирает от простоя, а не от возраста")
        void movesExpiryForward() {
            AuthSession session = session(AuthClient.NATIVE);
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));
            when(sessionRepository.rotate(any(), anyString(), anyString(), any(), any())).thenReturn(1);
            stubMinting();

            service.refresh(CURRENT_JTI, AuthClient.NATIVE);

            ArgumentCaptor<LocalDateTime> expiresAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(sessionRepository).rotate(any(), anyString(), anyString(), any(), expiresAt.capture());
            assertEquals(true, expiresAt.getValue().isAfter(LocalDateTime.now().plusDays(59)));
        }
    }

    @Nested
    @DisplayName("потерянный ответ")
    class LostResponse {

        @Test
        @DisplayName("предыдущий jti внутри окна отдаёт текущую пару, не двигая поколение")
        void reservesCurrentGeneration() {
            AuthSession session = rotatedSession(LocalDateTime.now().minusSeconds(5));
            when(sessionRepository.findByJti(PREVIOUS_JTI)).thenReturn(Optional.of(session));
            when(sessionRepository.touch(eq(session.getId()), eq(CURRENT_JTI), any(), any()))
                    .thenReturn(1);
            stubMinting();

            IssuedTokens tokens = service.refresh(PREVIOUS_JTI, AuthClient.NATIVE);

            assertEquals(CURRENT_JTI, tokens.refreshTokenId());
            verify(sessionRepository, never()).rotate(any(), anyString(), anyString(), any(), any());
            verify(sessionRepository, never()).revoke(any(), any(), any());
        }

        @Test
        @DisplayName("повторный ретрай тем же jti внутри окна тоже проходит — вторая потеря переживаема")
        void survivesASecondLoss() {
            AuthSession session = rotatedSession(LocalDateTime.now().minusSeconds(30));
            when(sessionRepository.findByJti(PREVIOUS_JTI)).thenReturn(Optional.of(session));
            when(sessionRepository.touch(any(), anyString(), any(), any())).thenReturn(1);
            stubMinting();

            assertEquals(CURRENT_JTI, service.refresh(PREVIOUS_JTI, AuthClient.NATIVE).refreshTokenId());
            assertEquals(CURRENT_JTI, service.refresh(PREVIOUS_JTI, AuthClient.NATIVE).refreshTokenId());
        }
    }

    @Nested
    @DisplayName("повтор за пределами окна — кража")
    class Replay {

        @Test
        @DisplayName("сессия отзывается целиком, а не просто отказ в запросе")
        void revokesWholeSession() {
            AuthSession session = rotatedSession(LocalDateTime.now().minusMinutes(10));
            when(sessionRepository.findByJti(PREVIOUS_JTI)).thenReturn(Optional.of(session));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh(PREVIOUS_JTI, AuthClient.NATIVE));

            verify(sessionRepository).revoke(eq(session.getId()), eq(SessionRevokeReason.REPLAY), any());
            verify(sessionRepository, never()).rotate(any(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("уже отозванная сессия рефреш не обслуживает")
        void refusesRevokedSession() {
            AuthSession session = session(AuthClient.NATIVE);
            session.setRevokedAt(LocalDateTime.now().minusHours(1));
            session.setRevokeReason(SessionRevokeReason.LOGOUT);
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh(CURRENT_JTI, AuthClient.NATIVE));
        }

        @Test
        @DisplayName("истёкшая сессия — тоже отказ")
        void refusesExpiredSession() {
            AuthSession session = session(AuthClient.NATIVE);
            session.setExpiresAt(LocalDateTime.now().minusDays(1));
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh(CURRENT_JTI, AuthClient.NATIVE));
        }

        @Test
        @DisplayName("токен без строки в реестре не усыновляется — это и есть смысл реестра")
        void refusesUnknownJti() {
            when(sessionRepository.findByJti("stranger")).thenReturn(Optional.empty());

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh("stranger", AuthClient.NATIVE));
        }
    }

    @Nested
    @DisplayName("границы клиентов")
    class ClientBoundary {

        @Test
        @DisplayName("веб-токен не обменивается на пару в теле ответа")
        void refusesWebTokenPresentedAsNative() {
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session(AuthClient.WEB)));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh(CURRENT_JTI, AuthClient.NATIVE));

            verify(sessionRepository, never()).rotate(any(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("нативный токен не принимается как cookie-шная сессия")
        void refusesNativeTokenPresentedAsWeb() {
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session(AuthClient.NATIVE)));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.refresh(CURRENT_JTI, AuthClient.WEB));
        }
    }

    @Nested
    @DisplayName("гонка параллельных рефрешей")
    class Concurrency {

        @Test
        @DisplayName("проигравший получает 409, а не вторую пару и не разлогин")
        void losingRotationConflicts() {
            AuthSession session = session(AuthClient.NATIVE);
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));
            when(sessionRepository.rotate(any(), anyString(), anyString(), any(), any())).thenReturn(0);
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(jwtProperties.getNativeRefreshExpiration()).thenReturn(NATIVE_REFRESH_SECONDS);

            assertThrows(ConflictStatusException.class,
                    () -> service.refresh(CURRENT_JTI, AuthClient.NATIVE));

            verify(sessionRepository, never()).revoke(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("логаут")
    class Logout {

        @Test
        @DisplayName("отзывает строку реестра — именно это заканчивает сессию")
        void revokesRegistryRow() {
            AuthSession session = session(AuthClient.NATIVE);
            when(sessionRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(session));

            service.closeByJti(CURRENT_JTI, AuthClient.NATIVE);

            verify(sessionRepository).revoke(eq(session.getId()), eq(SessionRevokeReason.LOGOUT), any());
        }
    }
}
