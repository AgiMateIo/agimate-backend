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
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.security.oauth2.OAuthLoginException;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderIdentityService — чей это провайдер и что за это пишется")
class ProviderIdentityServiceTest {

    private static final String REGISTRATION_ID = "github";
    private static final String PROVIDER_USER_ID = "30003";
    private static final String EMAIL = "ivan@example.com";

    @Mock
    private UserOAuthAccountRepository oAuthAccountRepository;
    @Mock
    private UserService userService;
    @Mock
    private MailService mailService;

    @InjectMocks
    private ProviderIdentityService service;

    private final OAuthUserAdapter adapter = mock(OAuthUserAdapter.class);

    @BeforeEach
    void describeAdapter() {
        when(adapter.providerType()).thenReturn(OAuthProviderType.GITHUB);
    }

    private void joinsByAddress(boolean allowed) {
        when(adapter.joinsExistingAccountByAddress()).thenReturn(allowed);
    }

    private static OAuthUserInfo userInfo(String email, boolean emailVerified, String displayName) {
        return new OAuthUserInfo(PROVIDER_USER_ID, email, emailVerified, "Иван", "Петров", displayName);
    }

    private static UserEntity user(String email) {
        UserEntity user = new UserEntity(email, "Иван", "Петров", "ivan");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static UserOAuthAccount account(OAuthProviderType provider, UserEntity owner) {
        return UserOAuthAccount.builder()
                .userEntity(owner)
                .oauthProvider(provider)
                .providerUserId(PROVIDER_USER_ID)
                .build();
    }

    private void noBinding() {
        when(oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(
                OAuthProviderType.GITHUB, PROVIDER_USER_ID)).thenReturn(Optional.empty());
    }

    /**
     * Порядок ответов и есть аргумент безопасности: пара решает всегда, когда может, и только когда
     * не может — спрашивается адрес. OpenID Connect Core §5.7: адрес не идентификатор.
     */
    @Nested
    @DisplayName("вход: пара (провайдер, id) решает первой")
    class ResolveByPair {

        @Test
        @DisplayName("привязка есть — её пользователь, по почте даже не ищем")
        void returnsBoundUser() {
            UserEntity owner = user(EMAIL);
            when(oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID))
                    .thenReturn(Optional.of(account(OAuthProviderType.GITHUB, owner)));

            assertSame(owner, service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null));

            verify(userService, never()).findByEmail(anyString());
            verify(oAuthAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("у известной привязки реферальный код игнорируется целиком")
        void ignoresCodeForBoundAccount() {
            UserEntity owner = user(EMAIL);
            when(oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID))
                    .thenReturn(Optional.of(account(OAuthProviderType.GITHUB, owner)));

            assertSame(owner, service.resolve(adapter, userInfo(EMAIL, true, "ivan"), "K7M2QX9F"));

            verify(userService, never()).findByReferralCode(any());
        }
    }

    @Nested
    @DisplayName("вход: адрес незнаком — заводится аккаунт")
    class ResolveCreates {

        @BeforeEach
        void nothingBoundYet() {
            noBinding();
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("без имени в профиле показываем почту")
        void createsWithEmailAsFallbackName() {
            UserEntity created = user(EMAIL);
            when(userService.createUser(EMAIL, "Иван", "Петров", EMAIL, null)).thenReturn(created);

            assertSame(created, service.resolve(adapter, userInfo(EMAIL, true, null), null));

            verify(oAuthAccountRepository).save(any());
        }

        /** Новому аккаунту сообщать не о чем: он и есть новость. */
        @Test
        @DisplayName("письма о новом способе входа нет — аккаунта секунду назад не было")
        void doesNotNotifyOnCreation() {
            when(userService.createUser(anyString(), any(), any(), any(), any())).thenReturn(user(EMAIL));

            service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null);

            verify(mailService, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("новому пользователю проставляется пригласивший")
        void attributesNewUser() {
            UserEntity referrer = user("ref@example.com");
            UserEntity created = user(EMAIL);
            when(userService.findByReferralCode("K7M2QX9F")).thenReturn(Optional.of(referrer));
            when(userService.createUser(EMAIL, "Иван", "Петров", "ivan", referrer.getId()))
                    .thenReturn(created);

            assertSame(created, service.resolve(adapter, userInfo(EMAIL, true, "ivan"), "K7M2QX9F"));
        }

        @Test
        @DisplayName("неизвестный код вход не ломает")
        void unknownCodeStillRegisters() {
            UserEntity created = user(EMAIL);
            when(userService.findByReferralCode("K7M2QX9F")).thenReturn(Optional.empty());
            when(userService.createUser(EMAIL, "Иван", "Петров", "ivan", null)).thenReturn(created);

            assertSame(created, service.resolve(adapter, userInfo(EMAIL, true, "ivan"), "K7M2QX9F"));
        }

        /** Cookie принадлежит клиенту, поэтому её значение проверяется на форме, а не на доверии. */
        @Test
        @DisplayName("подделанное значение реферальной cookie отбрасывается целиком")
        void ignoresForgedCookieValue() {
            when(userService.createUser(EMAIL, "Иван", "Петров", "ivan", null)).thenReturn(user(EMAIL));

            service.resolve(adapter, userInfo(EMAIL, true, "ivan"), "K7M2\r\nSet-Cookie: evil=1");

            verify(userService, never()).findByReferralCode(any());
        }
    }

    @Nested
    @DisplayName("вход: адрес ведёт в существующий аккаунт")
    class ResolveJoins {

        @BeforeEach
        void nothingBoundYet() {
            noBinding();
        }

        @Test
        @DisplayName("провайдер, которому это позволено, присоединяется к аккаунту")
        void joinsWhenDeclared() {
            UserEntity owner = user(EMAIL);
            joinsByAddress(true);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
            when(userService.findByIdForUpdate(owner.getId())).thenReturn(Optional.of(owner));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(
                    owner.getId(), OAuthProviderType.GITHUB)).thenReturn(List.of());

            assertSame(owner, service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null));

            ArgumentCaptor<UserOAuthAccount> saved = ArgumentCaptor.forClass(UserOAuthAccount.class);
            verify(oAuthAccountRepository).save(saved.capture());
            assertSame(owner, saved.getValue().getUserEntity());
            verify(userService, never()).createUser(anyString(), any(), any(), any(), any());
        }

        /** Молчаливое присоединение — единственный способ входа, появляющийся сам; о нём и пишем. */
        @Test
        @DisplayName("владелец узнаёт о появившемся способе входа письмом")
        void notifiesOnSilentJoin() {
            UserEntity owner = user(EMAIL);
            joinsByAddress(true);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
            when(userService.findByIdForUpdate(owner.getId())).thenReturn(Optional.of(owner));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(any(), any()))
                    .thenReturn(List.of());

            service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null);

            verify(mailService).send(anyString(), org.mockito.ArgumentMatchers.eq("provider-linked"), any());
        }

        /**
         * Право присоединяться объявляется адаптером и по умолчанию отсутствует: {@code emailVerified}
         * — слово адаптера, а не протокола, и пятый адаптер не должен наследовать последствия молча.
         */
        /** Пригласивший ставится один раз и навсегда: ссылка приводит новых, а не переписывает старых. */
        @Test
        @DisplayName("найденного по адресу не переатрибутируем — код даже не резолвится")
        void doesNotReattributeUserFoundByAddress() {
            UserEntity owner = user(EMAIL);
            joinsByAddress(true);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
            when(userService.findByIdForUpdate(owner.getId())).thenReturn(Optional.of(owner));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(any(), any()))
                    .thenReturn(List.of());

            service.resolve(adapter, userInfo(EMAIL, true, "ivan"), "K7M2QX9F");

            verify(userService, never()).findByReferralCode(any());
            verify(userService, never()).createUser(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("провайдер, которому это не позволено, в чужой аккаунт не входит")
        void refusesJoinWhenNotDeclared() {
            joinsByAddress(false);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user(EMAIL)));

            assertThrows(OAuthLoginException.class,
                    () -> service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null));

            verify(oAuthAccountRepository, never()).save(any());
        }

        /**
         * Раньше это молча создавало вторую строку, а после uq_user_oauth_accounts_user_id_oauth_provider
         * стало нарушением констрейнта и 500 на обычном входе. Это предложение, а не стектрейс.
         */
        @Test
        @DisplayName("у аккаунта уже другой аккаунт того же провайдера — внятный отказ, не 500")
        void refusesWhenProviderOccupied() {
            UserEntity owner = user(EMAIL);
            joinsByAddress(true);
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
            when(userService.findByIdForUpdate(owner.getId())).thenReturn(Optional.of(owner));
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(
                    owner.getId(), OAuthProviderType.GITHUB))
                    .thenReturn(List.of(account(OAuthProviderType.GITHUB, owner)));

            assertThrows(OAuthLoginException.class,
                    () -> service.resolve(adapter, userInfo(EMAIL, true, "ivan"), null));

            verify(oAuthAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("вход: провайдеру нечем поручиться за адрес")
    class ResolveRefuses {

        @Test
        @DisplayName("адреса нет вовсе — вход отклонён, ничего не записано")
        void rejectsLoginWithoutEmail() {
            noBinding();
            when(adapter.registrationId()).thenReturn(REGISTRATION_ID);

            assertThrows(OAuthLoginException.class,
                    () -> service.resolve(adapter, userInfo(null, true, "ivan"), null));

            verify(oAuthAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("адрес не подтверждён — вход отклонён: иначе это захват чужого аккаунта")
        void rejectsUnverifiedEmail() {
            noBinding();
            when(adapter.registrationId()).thenReturn(REGISTRATION_ID);

            assertThrows(OAuthLoginException.class,
                    () -> service.resolve(adapter, userInfo(EMAIL, false, "ivan"), null));

            verify(oAuthAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("привязка: аккаунт называет вызывающий, адрес не спрашивают")
    class Bind {

        private UserEntity owner;

        @BeforeEach
        void createOwner() {
            owner = user(EMAIL);
            when(userService.findByIdForUpdate(owner.getId())).thenReturn(Optional.of(owner));
        }

        @Test
        @DisplayName("адрес у провайдера может быть любым — и никаким")
        void bindsWhateverTheAddress() {
            noBinding();
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(
                    owner.getId(), OAuthProviderType.GITHUB)).thenReturn(List.of());

            ProviderIdentityService.LinkOutcome outcome = service.bind(owner.getId(),
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID, null, null, null);

            assertEquals(ProviderIdentityService.LinkOutcome.LINKED, outcome);
            ArgumentCaptor<UserOAuthAccount> saved = ArgumentCaptor.forClass(UserOAuthAccount.class);
            verify(oAuthAccountRepository).save(saved.capture());
            assertSame(owner, saved.getValue().getUserEntity());
            assertNull(saved.getValue().getEmail());
        }

        /** Слияние двух аккаунтов — отдельная большая работа, а не ветка в привязке. */
        @Test
        @DisplayName("чужой провайдер не забирается и аккаунты не сливаются")
        void refusesSomebodyElses() {
            when(oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(any(), anyString()))
                    .thenReturn(Optional.of(account(OAuthProviderType.GITHUB, user("other@example.com"))));

            ProviderIdentityService.LinkOutcome outcome = service.bind(owner.getId(),
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID, null, null, null);

            assertEquals(ProviderIdentityService.LinkOutcome.TAKEN, outcome);
            verify(oAuthAccountRepository, never()).save(any());
            verify(mailService, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("свой же провайдер повторно — не ошибка и не дубль")
        void idempotent() {
            when(oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(any(), anyString()))
                    .thenReturn(Optional.of(account(OAuthProviderType.GITHUB, owner)));

            ProviderIdentityService.LinkOutcome outcome = service.bind(owner.getId(),
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID, null, null, null);

            assertEquals(ProviderIdentityService.LinkOutcome.ALREADY_YOURS, outcome);
            verify(oAuthAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("второй аккаунт того же провайдера не привязывается")
        void refusesSecondAccountOfSameProvider() {
            noBinding();
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(
                    owner.getId(), OAuthProviderType.GITHUB))
                    .thenReturn(List.of(account(OAuthProviderType.GITHUB, owner)));

            ProviderIdentityService.LinkOutcome outcome = service.bind(owner.getId(),
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID, null, null, null);

            assertEquals(ProviderIdentityService.LinkOutcome.PROVIDER_OCCUPIED, outcome);
            verify(oAuthAccountRepository, never()).save(any());
        }

        /**
         * Две проверки и вставка — три оператора, между ними успевает другая привязка того же
         * человека: две вкладки, два нажатия. Строка берётся под замок раньше чтения — так же, как
         * это делает отвязка, — потому что нарушение констрейнта в PostgreSQL рвёт транзакцию
         * целиком, и разобраться с ним изнутри неё уже нельзя.
         */
        @Test
        @DisplayName("строка пользователя блокируется до чтения, а не после")
        void locksBeforeReading() {
            noBinding();
            when(oAuthAccountRepository.findByUserEntityIdAndOauthProvider(
                    owner.getId(), OAuthProviderType.GITHUB)).thenReturn(List.of());

            service.bind(owner.getId(), OAuthProviderType.GITHUB, PROVIDER_USER_ID, null, null, null);

            verify(userService).findByIdForUpdate(owner.getId());
            verify(userService, never()).findById(any());
        }
    }
}
