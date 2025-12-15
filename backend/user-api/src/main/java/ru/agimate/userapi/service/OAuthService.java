package ru.agimate.userapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final UserRepository userRepository;
    private final UserOAuthAccountRepository userOAuthAccountRepository;
    private final UserService userService;

    public Optional<UserOAuthAccount> findUserByProviderAndId(OAuthProviderType provider, String providerId) {
        return userOAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(provider, providerId);
    }

    public User createOrGetUserFromOAuth(OAuth2User oAuth2User, String registrationId) {
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String providerUserId = attributes.get("sub").toString(); // Google uses "sub", GitHub uses "id", etc.

        OAuthProviderType providerType = OAuthProviderType.fromString(registrationId);

        Optional<UserOAuthAccount> existingAccount = userOAuthAccountRepository
                .findByOauthProviderAndProviderUserIdWithUser(providerType, providerUserId);

        if (existingAccount.isPresent()) {
            return existingAccount.get().getUser();
        }

        // Check if user with this email already exists (in case of regular sign up first)
        String email = attributes.get("email").toString();

        String firstName = attributes.get("given_name") != null ? attributes.get("given_name").toString() : null;
        String lastName = attributes.get("family_name") != null ? attributes.get("family_name").toString() : null;
        String displayName = attributes.get("name") != null ? attributes.get("name").toString() : email;

        User user = userService.findByEmail(email)
                .orElseGet(() -> userService.createUser(email, firstName, lastName, displayName));

        // Create the OAuth account linking
        UserOAuthAccount oAuthAccount = UserOAuthAccount.builder()
                .user(user)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .oauthProvider(providerType)
                .providerUserId(providerUserId)
                .build();

        userOAuthAccountRepository.save(oAuthAccount);

        return user;
    }
}