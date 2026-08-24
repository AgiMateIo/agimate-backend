package ru.agimate.userapi.database.entities;

/**
 * What a one-time token from a letter buys. Stored as text and guarded by
 * {@code chk_auth_tokens_purpose} — a new value needs the constraint recreated in a migration.
 */
public enum AuthTokenPurpose {

    /**
     * Sets the password of the account the token belongs to. The same value serves «I forgot mine»
     * and «I signed in through Google and now want a password»: both are answered by proving that
     * the mailbox is yours, and that is one mechanism, not two.
     */
    PASSWORD_RESET
}
