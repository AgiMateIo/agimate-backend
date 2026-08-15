package ru.agimate.userapi.database.entities;

/**
 * What kind of client a session belongs to. It decides the token lifetimes and whether the refresh
 * token travels in a cookie or in the response body — not what the session is allowed to do.
 */
public enum AuthClient {

    /** Browser: refresh token lives in an httpOnly cookie, short-lived by mobile standards. */
    WEB,

    /** Installed application: refresh token is handed to the client and kept by it for months. */
    NATIVE
}
