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
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.PendingRegistration;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationService — регистрация с подтверждением адреса")
class RegistrationServiceTest {

    private static final String EMAIL = "ivan@example.com";
    private static final String PASSWORD = "correct horse battery";
    private static final String HASH = "{bcrypt}$2a$10$stored";
    private static final String LINK_BASE = "https://www.agimate.ru";

    @Mock
    private PendingRegistrationService pendingRegistrations;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserService userService;
    @Mock
    private AuthSessionService authSessionService;
    @Mock
    private PasswordAuthService passwordAuthService;
    @Mock
    private MailService mailService;

    @InjectMocks
    private RegistrationService service;

    private PendingRegistration pending;

    @BeforeEach
    void createPending() {
        pending = new PendingRegistration();
        pending.setEmail(EMAIL);
        pending.setDisplayName("Иван");
    }

    @Nested
    @DisplayName("заявка")
    class Register {

        @Test
        @DisplayName("свободный адрес получает письмо со ссылкой на свой домен")
        void freeAddress() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(pendingRegistrations.allowedToSend(EMAIL)).thenReturn(true);
            when(pendingRegistrations.issue(eq(EMAIL), eq("Иван"), isNull())).thenReturn("t0ken");

            service.register(EMAIL, "Иван", null, LINK_BASE);

            verify(mailService).send(eq(EMAIL), eq("registration-confirm"),
                    argThat((Map<String, String> vars) ->
                            (LINK_BASE + "/register/confirm?token=t0ken").equals(vars.get("link"))));
        }

        /**
         * Отказ «адрес занят» был бы проверялкой, зарегистрирован ли человек. Вместо него — письмо
         * тому, кто адресом владеет, и в нём ссылка добавить пароль к существующему аккаунту.
         */
        @Test
        @DisplayName("занятый адрес не отказ, а письмо «аккаунт уже есть»")
        void takenAddress() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(new UserEntity()));

            service.register(EMAIL, null, null, LINK_BASE);

            verify(passwordAuthService).requestReset(EMAIL, LINK_BASE, "account-exists");
            verify(pendingRegistrations, never()).issue(anyString(), any(), any());
        }

        @Test
        @DisplayName("частые заявки не превращают чужой ящик в оружие")
        void throttled() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(pendingRegistrations.allowedToSend(EMAIL)).thenReturn(false);

            service.register(EMAIL, null, null, LINK_BASE);

            verify(mailService, never()).send(anyString(), anyString(), any());
        }

        /** Адрес приводится к нижнему регистру до всего: иначе один ящик получил бы два аккаунта. */
        @Test
        @DisplayName("адрес складывается в нижний регистр до поиска")
        void foldsAddress() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(pendingRegistrations.allowedToSend(EMAIL)).thenReturn(true);
            when(pendingRegistrations.issue(eq(EMAIL), any(), isNull())).thenReturn("t0ken");

            service.register("  Ivan@Example.COM ", null, null, LINK_BASE);

            verify(mailService).send(eq(EMAIL), eq("registration-confirm"), any());
        }

        @Test
        @DisplayName("неизвестный реферальный код не мешает регистрации")
        void unknownReferral() {
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userService.findByReferralCode("NOSUCH12")).thenReturn(Optional.empty());
            when(pendingRegistrations.allowedToSend(EMAIL)).thenReturn(true);
            when(pendingRegistrations.issue(eq(EMAIL), any(), isNull())).thenReturn("t0ken");

            service.register(EMAIL, null, "NOSUCH12", LINK_BASE);

            verify(mailService).send(eq(EMAIL), eq("registration-confirm"), any());
        }
    }

    @Nested
    @DisplayName("переотправка")
    class Resend {

        @Test
        @DisplayName("токен выдаётся новый — прежний остался в письме, а не у нас")
        void reissues() {
            when(pendingRegistrations.findLive(EMAIL)).thenReturn(Optional.of(pending));
            when(pendingRegistrations.allowedToSend(EMAIL)).thenReturn(true);
            when(pendingRegistrations.issue(EMAIL, "Иван", null)).thenReturn("fresh");

            service.resend(EMAIL, LINK_BASE);

            verify(mailService).send(eq(EMAIL), eq("registration-confirm"),
                    argThat((Map<String, String> vars) ->
                            (LINK_BASE + "/register/confirm?token=fresh").equals(vars.get("link"))));
        }

        @Test
        @DisplayName("нечего переотправлять — молчание, а не отказ")
        void nothingWaiting() {
            when(pendingRegistrations.findLive(EMAIL)).thenReturn(Optional.empty());

            service.resend(EMAIL, LINK_BASE);

            verify(mailService, never()).send(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("подтверждение")
    class Confirm {

        @Test
        @DisplayName("аккаунт заводится здесь и не раньше, с паролем из формы подтверждения")
        void createsAccount() {
            UserEntity created = new UserEntity(EMAIL, null, null, "Иван");
            created.setId(UUID.randomUUID());
            when(pendingRegistrations.consume("t0ken")).thenReturn(pending);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userService.createUser(EMAIL, null, null, "Иван", null)).thenReturn(created);
            when(userService.updateUser(created)).thenReturn(created);

            service.confirm("t0ken", PASSWORD, AuthClient.WEB, "Pixel 8");

            assertEquals(HASH, created.getPasswordHash());
            assertNotNull(created.getPasswordUpdatedAt());
            verify(authSessionService).open(created, AuthClient.WEB, "Pixel 8");
        }

        /**
         * Пока письмо ждало, человек вошёл через провайдера. Адрес всё равно его — письмо доказывает
         * это не хуже провайдера, — поэтому пароль присоединяется к существующему аккаунту, а не
         * бьётся об уникальный индекс.
         */
        @Test
        @DisplayName("адрес успел обзавестись аккаунтом — пароль присоединяется к нему")
        void attachesToExisting() {
            UserEntity existing = new UserEntity(EMAIL, null, null, "Иван");
            existing.setId(UUID.randomUUID());
            when(pendingRegistrations.consume("t0ken")).thenReturn(pending);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
            when(userService.updateUser(existing)).thenReturn(existing);

            service.confirm("t0ken", PASSWORD, AuthClient.NATIVE, null);

            assertEquals(HASH, existing.getPasswordHash());
            verify(userService, never()).createUser(anyString(), any(), any(), anyString(), any());
            verify(authSessionService).open(existing, AuthClient.NATIVE, null);
        }

        /** Пароль задаёт тот, кто открыл письмо, — и проверяется он там же, до траты токена. */
        @Test
        @DisplayName("короткий пароль не тратит ссылку из письма")
        void shortPassword() {
            assertThrows(BadRequestStatusException.class,
                    () -> service.confirm("t0ken", "short", AuthClient.WEB, null));
            verify(pendingRegistrations, never()).consume(anyString());
        }

        @Test
        @DisplayName("без имени человек называется своим адресом")
        void displayNameFallsBackToEmail() {
            pending.setDisplayName(null);
            UserEntity created = new UserEntity(EMAIL, null, null, EMAIL);
            when(pendingRegistrations.consume("t0ken")).thenReturn(pending);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userService.createUser(EMAIL, null, null, EMAIL, null)).thenReturn(created);
            when(userService.updateUser(created)).thenReturn(created);

            service.confirm("t0ken", PASSWORD, AuthClient.WEB, null);

            verify(userService).createUser(EMAIL, null, null, EMAIL, null);
        }
    }
}
