package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.database.entities.OAuthProviderType;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class VkUserAdapter implements OAuthUserAdapter {

    public static final String REGISTRATION_ID = "vk";

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthProviderType providerType() {
        return OAuthProviderType.VK;
    }

    @Override
    public OAuthUserInfo extract(OAuth2User oAuth2User) {
        String firstName = oAuth2User.getAttribute("first_name");
        String lastName = oAuth2User.getAttribute("last_name");
        Object userId = oAuth2User.getAttributes().get("user_id");

        // VK confirms an address before binding it to the account; there is no per-field flag, and
        // an account registered by phone simply has no email at all.
        return new OAuthUserInfo(
                userId == null ? null : String.valueOf(userId),
                oAuth2User.getAttribute("email"),
                true,
                firstName,
                lastName,
                fullName(firstName, lastName)
        );
    }

    /** VK ID nests everything under {@code user}, while Spring needs {@code user_id} at the top. */
    @Override
    public Map<String, Object> normalize(OAuth2UserRequest userRequest, Map<String, Object> attributes) {
        if (!(attributes.get("user") instanceof Map<?, ?> user)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response"),
                    "VK ID returned no user object");
        }
        Map<String, Object> normalized = new HashMap<>();
        user.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private static String fullName(String firstName, String lastName) {
        return Stream.of(firstName, lastName)
                .filter(part -> part != null && !part.isBlank())
                .reduce((first, second) -> first + " " + second)
                .orElse(null);
    }
}
