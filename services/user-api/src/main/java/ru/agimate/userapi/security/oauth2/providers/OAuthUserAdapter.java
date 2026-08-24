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

    /**
     * Whether an address from this provider may lead a signing-in person into an account that
     * already exists here, rather than only into a new one.
     *
     * <p>Off unless a provider says otherwise, because {@link OAuthUserInfo#emailVerified()} is the
     * adapter's word and not the protocol's: two of the four shipped here answer it with a literal
     * {@code true} on reasoning about how that provider's accounts work. That reasoning is sound and
     * the four declare the right below — but it is a claim about somebody else's system, and an
     * adapter added later must not inherit the consequences of it by saying nothing. OpenID Connect
     * Core §5.7 is the underlying rule: an address is not an identifier, so treating one as an
     * identifier is a decision that has to be taken out loud.
     *
     * <p>A provider that does not declare it still opens accounts and still signs its own people in
     * — {@code (provider, providerUserId)} never needed this. What it cannot do is walk into an
     * account somebody else built.
     */
    default boolean joinsExistingAccountByAddress() {
        return false;
    }
}
