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
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthCode;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.AuthCodeRepository;
import ru.agimate.userapi.service.UserService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NativeAuthService — одноразовый код и обмен на токены")
class NativeAuthServiceTest {

    /** Пара из RFC 7636, приложение B — чтобы проверка PKCE не сверялась сама с собой. */
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final String CODE = "a1b2c3";
    private static final String REDIRECT_URI = "agimate://auth";

    @Mock
    private AuthCodeRepository codeRepository;
    @Mock
    private AuthSessionService sessionService;
    @Mock
    private UserService userService;

    @InjectMocks
    private NativeAuthService service;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity("ivan@example.com", "Иван", "Петров", "ivan");
        user.setId(UUID.randomUUID());
    }

    private AuthCode authCode() {
        AuthCode code = new AuthCode();
        code.setId(UUID.randomUUID());
        code.setCodeHash(CryptoUtils.sha256Hex(CODE));
        code.setUserId(user.getId());
        code.setCodeChallenge(CODE_CHALLENGE);
        code.setRedirectUri(REDIRECT_URI);
        code.setExpiresAt(LocalDateTime.now().plusSeconds(60));
        return code;
    }

    private void codeExists(AuthCode code) {
        when(codeRepository.findByCodeHash(CryptoUtils.sha256Hex(CODE))).thenReturn(Optional.of(code));
    }

    @Nested
    @DisplayName("выпуск кода")
    class Issue {

        @Test
        @DisplayName("в базу уезжает хеш, наружу — сам код")
        void storesHashNotCode() {
            String code = service.issueCode(user.getId(), CODE_CHALLENGE, REDIRECT_URI);

            ArgumentCaptor<AuthCode> captor = ArgumentCaptor.forClass(AuthCode.class);
            verify(codeRepository).save(captor.capture());
            assertNotEquals(code, captor.getValue().getCodeHash());
            assertEquals(CryptoUtils.sha256Hex(code), captor.getValue().getCodeHash());
            assertEquals(CODE_CHALLENGE, captor.getValue().getCodeChallenge());
            assertEquals(REDIRECT_URI, captor.getValue().getRedirectUri());
        }

        @Test
        @DisplayName("код живёт около минуты, а не до конца дня")
        void expiresWithinAMinute() {
            service.issueCode(user.getId(), CODE_CHALLENGE, REDIRECT_URI);

            ArgumentCaptor<AuthCode> captor = ArgumentCaptor.forClass(AuthCode.class);
            verify(codeRepository).save(captor.capture());
            assertEquals(true, captor.getValue().getExpiresAt()
                    .isBefore(LocalDateTime.now().plusSeconds(61)));
        }
    }

    @Nested
    @DisplayName("успешный обмен")
    class Exchange {

        @Test
        @DisplayName("код гасится, сессия заводится, строка кода указывает на неё")
        void spendsCodeAndOpensSession() {
            AuthCode code = authCode();
            IssuedTokens expected = new IssuedTokens(
                    UUID.randomUUID(), "access", 3600, "refresh", "jti");
            codeExists(code);
            when(codeRepository.claim(eq(code.getCodeHash()), any())).thenReturn(1);
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(sessionService.open(user, AuthClient.NATIVE, "Pixel 8")).thenReturn(expected);

            IssuedTokens tokens = service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, "Pixel 8");

            assertSame(expected, tokens);
            verify(codeRepository).attachSession(eq(code.getId()), eq(expected.sessionId()), any());
        }
    }

    @Nested
    @DisplayName("отказы")
    class Refusals {

        @Test
        @DisplayName("неизвестный код")
        void unknownCode() {
            when(codeRepository.findByCodeHash(anyString())).thenReturn(Optional.empty());

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, null));
        }

        @Test
        @DisplayName("чужой verifier — код без него бесполезен, ради этого он и одноразовый")
        void wrongVerifier() {
            codeExists(authCode());

            assertThrows(ForbiddenStatusException.class, () -> service.exchange(
                    CODE, "wsGZ1cKLYCJK3pTFPMxUw0qXNhBnCJK3pTFPMxUw0qX", REDIRECT_URI, null));

            verify(codeRepository, never()).claim(anyString(), any());
        }

        @Test
        @DisplayName("verifier не той формы отклоняется до похода в базу")
        void malformedVerifier() {
            assertThrows(BadRequestStatusException.class,
                    () -> service.exchange(CODE, "short", REDIRECT_URI, null));

            verify(codeRepository, never()).findByCodeHash(anyString());
        }

        @Test
        @DisplayName("просроченный код")
        void expiredCode() {
            AuthCode code = authCode();
            code.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            codeExists(code);

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, null));
        }

        @Test
        @DisplayName("другой redirect_uri — обмен привязан к адресу, с которого начинали")
        void redirectUriMismatch() {
            codeExists(authCode());

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, "agimate://elsewhere", null));
        }

        @Test
        @DisplayName("гонка двух обменов: проигравший уходит ни с чем")
        void concurrentExchange() {
            AuthCode code = authCode();
            codeExists(code);
            when(codeRepository.claim(anyString(), any())).thenReturn(0);

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, null));

            verify(sessionService, never()).open(any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("повторный обмен")
    class Replay {

        @Test
        @DisplayName("отзывает сессию, которую выдал первый обмен")
        void revokesSessionMintedByTheFirstExchange() {
            AuthCode code = authCode();
            UUID sessionId = UUID.randomUUID();
            code.setUsedAt(LocalDateTime.now().minusSeconds(10));
            code.setSessionId(sessionId);
            codeExists(code);

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, null));

            verify(sessionService).revoke(sessionId, SessionRevokeReason.REPLAY);
        }

        @Test
        @DisplayName("без записанной сессии отзывать нечего, но обмена всё равно нет")
        void refusesEvenWithoutASessionToRevoke() {
            AuthCode code = authCode();
            code.setUsedAt(LocalDateTime.now().minusSeconds(10));
            codeExists(code);

            assertThrows(ForbiddenStatusException.class,
                    () -> service.exchange(CODE, CODE_VERIFIER, REDIRECT_URI, null));

            verify(sessionService, never()).revoke(any(), any());
        }
    }
}
