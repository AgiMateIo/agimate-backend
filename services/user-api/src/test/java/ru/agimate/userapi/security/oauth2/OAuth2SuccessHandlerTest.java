package ru.agimate.userapi.security.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapters;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.UserService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2SuccessHandler — заведение и связывание аккаунтов")
class OAuth2SuccessHandlerTest {

    private static final String REGISTRATION_ID = "github";
    private static final String PROVIDER_USER_ID = "30003";
    private static final String EMAIL = "ivan@example.com";

    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private UserOAuthAccountRepository userOAuthAccountRepository;
    @Mock
    private UserService userService;
    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private OAuthUserAdapters adapters;

    @InjectMocks
    private OAuth2SuccessHandler handler;

    private final OAuthUserAdapter adapter = mock(OAuthUserAdapter.class);
    private final OAuth2User principal = mock(OAuth2User.class);

    @BeforeEach
    void bindAdapter() {
        when(adapters.require(REGISTRATION_ID)).thenReturn(adapter);
    }

    private void provides(OAuthUserInfo userInfo) {
        when(adapter.extract(principal)).thenReturn(userInfo);
        when(adapter.providerType()).thenReturn(OAuthProviderType.GITHUB);
    }

    private static OAuthUserInfo userInfo(String email, boolean emailVerified, String displayName) {
        return new OAuthUserInfo(PROVIDER_USER_ID, email, emailVerified, "Иван", "Петров", displayName);
    }

    private static UserEntity existingUser() {
        return new UserEntity(EMAIL, "Иван", "Петров", "ivan");
    }

    @Nested
    @DisplayName("привязка уже есть")
    class KnownAccount {

        @Test
        @DisplayName("возвращается её пользователь, поиска по почте не происходит")
        void returnsBoundUser() {
            UserEntity user = existingUser();
            UserOAuthAccount account = UserOAuthAccount.builder().userEntity(user).build();
            provides(userInfo(EMAIL, true, "ivan"));
            when(userOAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID)).thenReturn(Optional.of(account));

            assertSame(user, handler.createOrGetUserFromOAuth(principal, REGISTRATION_ID));

            verifyNoInteractions(userService);
            verify(userOAuthAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("привязки ещё нет")
    class NewAccount {

        @BeforeEach
        void noBinding() {
            when(userOAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(
                    OAuthProviderType.GITHUB, PROVIDER_USER_ID)).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("почта уже известна — вход через второго провайдера ведёт в тот же аккаунт")
        void linksToUserWithSameEmail() {
            UserEntity user = existingUser();
            provides(userInfo(EMAIL, true, "ivan"));
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));

            assertSame(user, handler.createOrGetUserFromOAuth(principal, REGISTRATION_ID));

            ArgumentCaptor<UserOAuthAccount> captor = ArgumentCaptor.forClass(UserOAuthAccount.class);
            verify(userOAuthAccountRepository).save(captor.capture());
            assertSame(user, captor.getValue().getUserEntity());
            assertEquals(OAuthProviderType.GITHUB, captor.getValue().getOauthProvider());
            assertEquals(EMAIL, captor.getValue().getEmail());
            verify(userService, never()).createUser(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("почта незнакомая — заводится пользователь, без имени в профиле показываем почту")
        void createsUserWithEmailAsFallbackName() {
            UserEntity created = existingUser();
            provides(userInfo(EMAIL, true, null));
            when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userService.createUser(EMAIL, "Иван", "Петров", EMAIL)).thenReturn(created);

            assertSame(created, handler.createOrGetUserFromOAuth(principal, REGISTRATION_ID));

            verify(userOAuthAccountRepository).save(any());
        }

        @Test
        @DisplayName("провайдер не дал почты — вход отклонён, ничего не записано")
        void rejectsLoginWithoutEmail() {
            provides(userInfo(null, true, "ivan"));

            assertThrows(OAuthLoginException.class,
                    () -> handler.createOrGetUserFromOAuth(principal, REGISTRATION_ID));

            verifyNoInteractions(userService);
            verify(userOAuthAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("почта не подтверждена — вход отклонён: иначе это захват чужого аккаунта")
        void rejectsUnverifiedEmail() {
            provides(userInfo(EMAIL, false, "ivan"));

            assertThrows(OAuthLoginException.class,
                    () -> handler.createOrGetUserFromOAuth(principal, REGISTRATION_ID));

            verifyNoInteractions(userService);
            verify(userOAuthAccountRepository, never()).save(any());
        }
    }
}
