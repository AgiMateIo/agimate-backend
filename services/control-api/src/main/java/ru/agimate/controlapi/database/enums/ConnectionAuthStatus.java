package ru.agimate.controlapi.database.enums;

/**
 * Authorisation state of a connector instance. Orthogonal to {@code enabled}: that one carries the
 * user's intent, this one the state of the system — mixing them would resurrect a manually disabled
 * connection on re-authorisation.
 */
public enum ConnectionAuthStatus {

    /** Ready to work: static credentials, or an OAuth grant that is still alive. */
    AUTHORIZED,

    /** The row exists, the tokens do not — the user has not finished (or not started) the OAuth flow. */
    PENDING_AUTH,

    /** The refresh token was revoked, expired, or never issued: only the user can fix this. */
    AUTH_EXPIRED
}
