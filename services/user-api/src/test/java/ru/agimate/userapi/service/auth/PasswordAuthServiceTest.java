package ru.agimate.userapi.service.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordAuthService — вход паролем и письмо, которым пароль заводится")
class PasswordAuthServiceTest {

    private static final String EMAIL = "ivan@example.com";
    private static final String PASSWORD = "correct horse battery";
    private static final String HASH = "{bcrypt}$2a$10$stored";
    private static final String LINK_BASE = "https://www.agimate.ru";

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserService userService;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private AuthSessionService authSessionService;
    @Mock
    private MailService mailService;
    @Mock
    private LoginRateLimiter rateLimiter;

    @InjectMocks
    private PasswordAuthService service;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity(EMAIL, "Иван", "Петров", "ivan");
        user.setId(UUID.randomUUID());
        user.setPasswordHash(HASH);
        // @PostConstruct не вызывается при @InjectMocks, а сравнение с этим хешем — часть входа:
        // без него неизвестный адрес отвечал бы быстрее неверного пароля.
        when(passwordEncoder.encode(anyString())).thenReturn(HASH);
        service.prepareAbsentPasswordHash();
    }

    @Nested
    @DisplayName("вход")
    class Login {

        @Test
        @DisplayName("верный пароль открывает сессию")
        void succeeds() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

            service.login(EMAIL, PASSWORD, AuthClient.WEB, "Pixel 8");

            verify(authSessionService).open(user, AuthClient.WEB, "Pixel 8");
            verify(rateLimiter).recordSuccess(EMAIL);
        }

        @Test
        @DisplayName("неверный пароль — 401 и попытка на счётчике")
        void wrongPassword() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

            assertThrows(UnauthorizedStatusException.class,
                    () -> service.login(EMAIL, PASSWORD, AuthClient.WEB, null));
            verify(rateLimiter).recordFailure(EMAIL);
            verify(authSessionService, never()).open(any(), any(), any());
        }

        /**
         * Иначе несуществующий адрес отвечал бы заметно быстрее неверного пароля, и эндпойнт
         * превратился бы в проверялку, зарегистрирован ли человек.
         */
        @Test
        @DisplayName("неизвестный адрес стоит того же сравнения, что и неверный пароль")
        void unknownEmailCostsTheSameComparison() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThrows(UnauthorizedStatusException.class,
                    () -> service.login(EMAIL, PASSWORD, AuthClient.WEB, null));
            verify(passwordEncoder, atLeastOnce()).matches(eq(PASSWORD), anyString());
        }

        @Test
        @DisplayName("аккаунт без пароля входа паролем не даёт")
        void accountWithoutPassword() {
            user.setPasswordHash(null);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));

            assertThrows(UnauthorizedStatusException.class,
                    () -> service.login(EMAIL, PASSWORD, AuthClient.WEB, null));
        }

        @Test
        @DisplayName("после лимита попыток — 429, до базы дело не доходит")
        void blocked() {
            when(rateLimiter.blocked(EMAIL)).thenReturn(true);
            when(rateLimiter.window()).thenReturn(Duration.ofMinutes(15));

            assertThrows(TooManyRequestsStatusException.class,
                    () -> service.login(EMAIL, PASSWORD, AuthClient.WEB, null));
            verify(userService, never()).findByEmail(anyString());
        }
    }

    @Nested
    @DisplayName("письмо с паролем")
    class RequestReset {

        @Test
        @DisplayName("ссылка строится на домене, с которого пришёл запрос")
        void sendsLetter() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(authTokenService.allowedToSend(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(true);
            when(authTokenService.issue(eq(user.getId()), eq(AuthTokenPurpose.PASSWORD_RESET), any()))
                    .thenReturn("t0ken");

            service.requestReset(EMAIL, LINK_BASE);

            verify(mailService).send(eq(EMAIL), eq("password-reset"), org.mockito.ArgumentMatchers
                    .argThat((Map<String, String> vars) ->
                            (LINK_BASE + "/password/reset?token=t0ken").equals(vars.get("link"))));
        }

        /** Ответ вызывающему одинаков всегда, поэтому отсутствие аккаунта — это молчание. */
        @Test
        @DisplayName("неизвестный адрес не порождает ни токена, ни письма")
        void unknownEmail() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());

            service.requestReset(EMAIL, LINK_BASE);

            verify(authTokenService, never()).issue(any(), any(), any());
            verify(mailService, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("частые просьбы не превращают чужой ящик в оружие")
        void throttled() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(authTokenService.allowedToSend(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(false);

            service.requestReset(EMAIL, LINK_BASE);

            verify(mailService, never()).send(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("сброс по ссылке")
    class Reset {

        @Test
        @DisplayName("пароль записан, все сессии отозваны — включая текущую")
        void resets() {
            when(authTokenService.consume("t0ken", AuthTokenPurpose.PASSWORD_RESET)).thenReturn(user.getId());
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.encode(PASSWORD)).thenReturn("{bcrypt}$2a$10$new");

            service.reset("t0ken", PASSWORD);

            verify(userService).updateUser(user);
            verify(authSessionService).revokeAllForUser(
                    user.getId(), SessionRevokeReason.PASSWORD_CHANGED, null);
        }

        @Test
        @DisplayName("короткий пароль не доходит до токена")
        void tooShort() {
            assertThrows(BadRequestStatusException.class, () -> service.reset("t0ken", "short"));
            verify(authTokenService, never()).consume(anyString(), any());
        }

        /**
         * Bcrypt читает 72 байта и молчит об остальном. Кириллица — два байта на символ, поэтому
         * граница считается в байтах, а не в символах: пароль из 40 букв уже за ней.
         */
        @Test
        @DisplayName("длинный пароль отвергается по байтам, а не по символам")
        void tooLongInBytes() {
            String password = "п".repeat(40);
            org.junit.jupiter.api.Assertions.assertTrue(
                    password.length() < 72 && password.getBytes(StandardCharsets.UTF_8).length > 72);

            assertThrows(BadRequestStatusException.class, () -> service.reset("t0ken", password));
        }
    }

    @Nested
    @DisplayName("смена пароля")
    class Change {

        @Test
        @DisplayName("текущая сессия остаётся, остальные заканчиваются")
        void keepsCurrentSession() {
            UUID sessionId = UUID.randomUUID();
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("old-password", HASH)).thenReturn(true);
            when(passwordEncoder.encode(PASSWORD)).thenReturn("{bcrypt}$2a$10$new");

            service.change(user.getId(), sessionId, "old-password", PASSWORD);

            verify(authSessionService).revokeAllForUser(
                    user.getId(), SessionRevokeReason.PASSWORD_CHANGED, sessionId);
        }

        @Test
        @DisplayName("не тот текущий пароль — отказ")
        void wrongCurrent() {
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("nope", HASH)).thenReturn(false);

            assertThrows(BadRequestStatusException.class,
                    () -> service.change(user.getId(), UUID.randomUUID(), "nope", PASSWORD));
            verify(authSessionService, never()).revokeAllForUser(any(), any(), any());
        }

        /** Вошедшему через провайдера предъявить нечего — ему в письмо, а не сюда. */
        @Test
        @DisplayName("у аккаунта без пароля менять нечего")
        void noPasswordYet() {
            user.setPasswordHash(null);
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));

            assertThrows(BadRequestStatusException.class,
                    () -> service.change(user.getId(), UUID.randomUUID(), "whatever", PASSWORD));
            verify(userService, never()).updateUser(any());
        }
    }

    @Nested
    @DisplayName("отзыв сессий")
    class Revocation {

        @Test
        @DisplayName("сброс отзывает всё, не сохраняя ни одной сессии")
        void resetKeepsNothing() {
            when(authTokenService.consume(anyString(), any())).thenReturn(user.getId());
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}$2a$10$new");

            service.reset("t0ken", PASSWORD);

            verify(authSessionService).revokeAllForUser(any(), any(), isNull());
        }
    }
}
