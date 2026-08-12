package ru.agimate.userapi.security.oauth2.providers;

/**
 * Provider-independent view of the person who has just signed in.
 *
 * @param providerUserId stable id at the provider — the only field that identifies the account
 * @param email          may be null: a provider is free to give none (a VK account bound to a phone)
 * @param emailVerified  whether the provider vouches for the address; an unverified one must never
 *                       be matched against an existing user, that is account takeover
 * @param displayName    may be null; the caller falls back to the email
 */
public record OAuthUserInfo(
        String providerUserId,
        String email,
        boolean emailVerified,
        String firstName,
        String lastName,
        String displayName
) {
}
