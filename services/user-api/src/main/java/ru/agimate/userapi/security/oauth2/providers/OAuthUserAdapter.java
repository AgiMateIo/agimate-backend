package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ru.agimate.userapi.database.entities.OAuthProviderType;

import java.util.Map;

/**
 * Everything one provider does differently: the shape of its user-info response and the way its
 * fields map onto {@link OAuthUserInfo}.
 */
public interface OAuthUserAdapter {

    /** Spring Security registration id — the {@code spring.security.oauth2.client.registration.*} key. */
    String registrationId();

    OAuthProviderType providerType();

    OAuthUserInfo extract(OAuth2User oAuth2User);

    /**
     * Reshapes the raw user-info body before Spring builds the principal from it. Spring needs the
     * user-name attribute at the top level, and some providers hide it one level down or leave the
     * email out of the response entirely.
     *
     * <p>Not called for OpenID Connect providers (Google): there the principal comes from the id
     * token through {@code OidcUserService}, which has no such hook.
     */
    default Map<String, Object> normalize(OAuth2UserRequest userRequest, Map<String, Object> attributes) {
        return attributes;
    }
}
