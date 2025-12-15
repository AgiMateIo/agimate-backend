package ru.agimate.userapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserOAuthAccountRepository userOAuthAccountRepository;

    @Mock
    private UserService userService;

    private OAuthService oAuthService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        oAuthService = new OAuthService(userRepository, userOAuthAccountRepository, userService);
    }

    @Test
    void testFindUserByProviderAndId() {
        // Given
        String providerId = "12345";
        OAuthProviderType providerType = OAuthProviderType.GOOGLE;
        UserOAuthAccount expectedAccount = new UserOAuthAccount();
        when(userOAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(providerType, providerId))
                .thenReturn(Optional.of(expectedAccount));

        // When
        Optional<UserOAuthAccount> result = oAuthService.findUserByProviderAndId(providerType, providerId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(expectedAccount, result.get());
        verify(userOAuthAccountRepository).findByOauthProviderAndProviderUserIdWithUser(providerType, providerId);
    }

    @Test
    void testCreateOrGetUserFromOAuth_NewUser() {
        // Given
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "12345");
        attributes.put("email", "test@example.com");
        attributes.put("given_name", "John");
        attributes.put("family_name", "Doe");
        attributes.put("name", "John Doe");
        
        OAuth2User oAuth2User = new DefaultOAuth2User(
            Collections.emptyList(),
            attributes,
            "sub"
        );

        when(userOAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(OAuthProviderType.GOOGLE, "12345"))
                .thenReturn(Optional.empty());
        when(userService.findByEmail("test@example.com"))
                .thenReturn(Optional.empty());

        User newUser = new User("test@example.com", "John", "Doe", "John Doe");
        when(userService.createUser("test@example.com", "John", "Doe", "John Doe"))
                .thenReturn(newUser);

        UserOAuthAccount linkedAccount = new UserOAuthAccount(newUser, OAuthProviderType.GOOGLE, "12345");
        when(userOAuthAccountRepository.save(any(UserOAuthAccount.class)))
                .thenReturn(linkedAccount);

        // When
        User result = oAuthService.createOrGetUserFromOAuth(oAuth2User, "google");

        // Then
        assertEquals(newUser, result);
        verify(userService).createUser("test@example.com", "John", "Doe", "John Doe");
        verify(userOAuthAccountRepository).save(any(UserOAuthAccount.class));
    }
}