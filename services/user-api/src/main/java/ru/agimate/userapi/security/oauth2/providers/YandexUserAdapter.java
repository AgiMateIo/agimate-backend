package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.database.entities.OAuthProviderType;

@Component
public class YandexUserAdapter implements OAuthUserAdapter {

    public static final String REGISTRATION_ID = "yandex";

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthProviderType providerType() {
        return OAuthProviderType.YANDEX;
    }

    @Override
    public OAuthUserInfo extract(OAuth2User oAuth2User) {
        // default_email is the address Yandex delivers mail to, so the account demonstrably controls
        // it — there is no separate verification flag to read.
        return new OAuthUserInfo(
                oAuth2User.getAttribute("id"),
                oAuth2User.getAttribute("default_email"),
                true,
                oAuth2User.getAttribute("first_name"),
                oAuth2User.getAttribute("last_name"),
                oAuth2User.getAttribute("display_name")
        );
    }
}
