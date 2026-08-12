package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.database.entities.OAuthProviderType;

@Component
public class GoogleUserAdapter implements OAuthUserAdapter {

    public static final String REGISTRATION_ID = "google";

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthProviderType providerType() {
        return OAuthProviderType.GOOGLE;
    }

    @Override
    public OAuthUserInfo extract(OAuth2User oAuth2User) {
        return new OAuthUserInfo(
                oAuth2User.getAttribute("sub"),
                oAuth2User.getAttribute("email"),
                Boolean.TRUE.equals(oAuth2User.getAttribute("email_verified")),
                oAuth2User.getAttribute("given_name"),
                oAuth2User.getAttribute("family_name"),
                oAuth2User.getAttribute("name")
        );
    }
}
