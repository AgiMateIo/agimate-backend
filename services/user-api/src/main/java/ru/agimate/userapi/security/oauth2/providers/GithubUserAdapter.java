package ru.agimate.userapi.security.oauth2.providers;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import ru.agimate.userapi.database.entities.OAuthProviderType;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GithubUserAdapter implements OAuthUserAdapter {

    public static final String REGISTRATION_ID = "github";

    private final GithubEmailClient emailClient;

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthProviderType providerType() {
        return OAuthProviderType.GITHUB;
    }

    @Override
    public OAuthUserInfo extract(OAuth2User oAuth2User) {
        Object id = oAuth2User.getAttributes().get("id");
        String name = oAuth2User.getAttribute("name");
        String login = oAuth2User.getAttribute("login");
        // Whatever email is here was put there by normalize(), which takes verified ones only.
        String email = oAuth2User.getAttribute("email");

        // GitHub keeps one free-form name, so splitting it into first/last would be guesswork.
        return new OAuthUserInfo(
                id == null ? null : String.valueOf(id),
                email,
                email != null,
                null,
                null,
                name != null ? name : login
        );
    }

    /**
     * Replaces the public email of {@code /user} — absent for most accounts and not necessarily
     * confirmed — with the primary verified one.
     */
    @Override
    public Map<String, Object> normalize(OAuth2UserRequest userRequest, Map<String, Object> attributes) {
        Map<String, Object> normalized = new HashMap<>(attributes);
        normalized.remove("email");
        try {
            emailClient.primaryVerifiedEmail(userRequest.getAccessToken().getTokenValue())
                    .ifPresent(email -> normalized.put("email", email));
        } catch (RestClientException ex) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("github_emails_unavailable"),
                    "Could not read the email addresses of the GitHub account", ex);
        }
        return normalized;
    }
}
