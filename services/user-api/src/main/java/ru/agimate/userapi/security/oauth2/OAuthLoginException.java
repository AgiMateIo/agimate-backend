package ru.agimate.userapi.security.oauth2;

/**
 * The provider authenticated the person, but the account it describes cannot be admitted — no
 * address to register them under, or one the provider does not vouch for. The message is shown to
 * the user, so it has to say what to do about it.
 */
public class OAuthLoginException extends RuntimeException {

    public OAuthLoginException(String message) {
        super(message);
    }
}
