package ru.agimate.userapi.database.entities;

public enum OAuthProviderType {
    GOOGLE,
    YANDEX;

    public static OAuthProviderType fromString(String provider) {
        for (OAuthProviderType type : OAuthProviderType.values()) {
            if (type.name().equalsIgnoreCase(provider)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No OAuth provider found for: " + provider);
    }
}