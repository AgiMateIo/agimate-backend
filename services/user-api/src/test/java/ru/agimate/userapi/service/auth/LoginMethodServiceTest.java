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
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.ProviderLinkProof;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginMethodService — способы входа в один аккаунт")
class LoginMethodServiceTest {

    private static final String PROOF = "0a1b2c";
    private static final String PROVIDER_USER_ID = "1234567890";

    @Mock
    private UserOAuthAccountRepository oAuthAccountRepository;
    @Mock
    private ProviderIdentityService providerIdentityService;
    @Mock
    private ProviderLinkProofService linkProofService;
    @Mock
    private UserService userService;
    @Mock
    private MailService mailService;

    @InjectMocks
    private LoginMethodService service;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity("ivan@example.com", "Иван", "Петров", "ivan");
        user.setId(UUID.randomUUID());
    }

    private UserOAuthAccount account(OAuthProviderType provider, UserEntity owner) {
        return UserOAuthAccount.builder()
                .userEntity(owner)
                .oauthProvider(provider)
                .providerUserId(PROVIDER_USER_ID)
                .email("ivan@example.com")
                .build();
    }

    /**
     * Привязка здесь только погашает доказательство и зовёт владельца таблицы: правила про чужой
     * провайдер и второй аккаунт того же провайдера проверяются в {@link ProviderIdentityServiceTest}.
     * Смысл этого класса — что аккаунт называет сам вызов, а не то, что приехало из браузера.
     */
    @Nested
    @DisplayName("погашение доказательства привязки")
    class RedeemProof {

        private ProviderLinkProof proof(OAuthProviderType provider) {
            ProviderLinkProof row = new ProviderLinkProof();
            row.setOauthProvider(provider);
            row.setProviderUserId(PROVIDER_USER_ID);
            row.setEmail("other@mailbox.org");
            return row;
        }

        @Test
        @DisplayName("провайдер берётся из доказательства, аккаунт — из вызывающего")
        void bindsToTheCaller() {
            when(linkProofService.consume(PROOF)).thenReturn(proof(OAuthProviderType.GITHUB));
            when(providerIdentityService.bind(user.getId(), OAuthProviderType.GITHUB, PROVIDER_USER_ID,
                    "other@mailbox.org", null, null))
                    .thenReturn(ProviderIdentityService.LinkOutcome.LINKED);

            LoginMethodService.LinkResult result = service.redeemLinkProof(user.getId(), PROOF);

            assertEquals(OAuthProviderType.GITHUB, result.provider());
            assertEquals(ProviderIdentityService.LinkOutcome.LINKED, result.outcome());
        }

        /** Доказательство не называет аккаунт вовсе — иначе привязку решал бы тот, кто прошёл круг. */
        @Test
        @DisplayName("негодное доказательство — ничего не связывается")
        void refusesSpentProof() {
            when(linkProofService.consume(PROOF))
                    .thenThrow(new ru.agimate.common.rest.error.ForbiddenStatusException("nope"));

            assertThrows(ru.agimate.common.rest.error.ForbiddenStatusException.class,
                    () -> service.redeemLinkProof(user.getId(), PROOF));
            verify(providerIdentityService, never()).bind(any(), any(), anyString(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("отвязка")
    class Unlink {

        @Test
        @DisplayName("при двух способах провайдер отвязывается")
        void unlinks() {
            UserOAuthAccount google = account(OAuthProviderType.GOOGLE, user);
            user.setPasswordHash("{bcrypt}$2a$10$stored");
            when(userService.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(user.getId(), OAuthProviderType.GOOGLE))
                    .thenReturn(List.of(google));
            when(oAuthAccountRepository.countByUserEntityId(user.getId())).thenReturn(1L);

            service.unlinkProvider(user.getId(), OAuthProviderType.GOOGLE);

            verify(oAuthAccountRepository).delete(google);
            verify(mailService).send(eq("ivan@example.com"), eq("provider-unlinked"), any());
        }

        /** Иначе человек выходит из собственного аккаунта, и вернуть его может только администратор. */
        @Test
        @DisplayName("последний способ входа отвязать нельзя")
        void refusesTheLastOne() {
            when(userService.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(any(), any()))
                    .thenReturn(List.of(account(OAuthProviderType.GOOGLE, user)));
            when(oAuthAccountRepository.countByUserEntityId(user.getId())).thenReturn(1L);

            assertThrows(BadRequestStatusException.class,
                    () -> service.unlinkProvider(user.getId(), OAuthProviderType.GOOGLE));
            verify(oAuthAccountRepository, never()).delete(any());
        }

        @Test
        @DisplayName("пароль как последний способ тоже не убрать")
        void refusesTheLastPassword() {
            user.setPasswordHash("{bcrypt}$2a$10$stored");
            when(userService.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.countByUserEntityId(user.getId())).thenReturn(0L);

            assertThrows(BadRequestStatusException.class, () -> service.dropPassword(user.getId()));
            verify(userService, never()).updateUser(any());
        }

        @Test
        @DisplayName("пароль при живом провайдере убирается")
        void dropsPassword() {
            user.setPasswordHash("{bcrypt}$2a$10$stored");
            when(userService.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.countByUserEntityId(user.getId())).thenReturn(1L);

            service.dropPassword(user.getId());

            assertNull(user.getPasswordHash());
            verify(userService).updateUser(user);
            verify(mailService).send(eq("ivan@example.com"), eq("password-removed"), any());
        }

        @Test
        @DisplayName("нечего отвязывать — 404, а не тихий успех")
        void nothingToUnlink() {
            when(userService.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(any(), any()))
                    .thenReturn(List.of());

            assertThrows(NotFoundStatusException.class,
                    () -> service.unlinkProvider(user.getId(), OAuthProviderType.VK));
        }
    }

    @Nested
    @DisplayName("листинг")
    class Listing {

        @Test
        @DisplayName("провайдеры и пароль в одном списке, пароль последним")
        void listsBoth() {
            user.setPasswordHash("{bcrypt}$2a$10$stored");
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.findByUserEntityIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(account(OAuthProviderType.GOOGLE, user)));

            List<LoginMethodService.LoginMethod> methods = service.list(user.getId());

            assertEquals(2, methods.size());
            assertEquals(OAuthProviderType.GOOGLE, methods.get(0).provider());
            assertEquals(LoginMethodService.LoginMethod.Kind.PASSWORD, methods.get(1).kind());
        }

        @Test
        @DisplayName("без пароля в списке только провайдеры")
        void listsProvidersOnly() {
            when(userService.findById(user.getId())).thenReturn(Optional.of(user));
            when(oAuthAccountRepository.findByUserEntityIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(account(OAuthProviderType.GOOGLE, user)));

            assertEquals(1, service.list(user.getId()).size());
        }
    }
}
