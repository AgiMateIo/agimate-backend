package ru.agimate.userapi.database.entities;

import lombok.Getter;

/**
 * The providers an installation may offer. The display name is a brand and is not translated — it is
 * written the way the provider writes it, in every language of every letter.
 */
@Getter
public enum OAuthProviderType {

    GOOGLE("Google"),
    YANDEX("Yandex"),
    GITHUB("GitHub"),
    VK("VK ID");

    private final String displayName;

    OAuthProviderType(String displayName) {
        this.displayName = displayName;
    }
}
