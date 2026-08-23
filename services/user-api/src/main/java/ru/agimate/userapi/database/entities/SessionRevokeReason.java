package ru.agimate.userapi.database.entities;

/**
 * Why a session stopped being usable. Kept because the three cases read very differently in an
 * incident: a logout is routine, a replay is a stolen token, a manual revoke is somebody acting on
 * a device list.
 */
public enum SessionRevokeReason {

    LOGOUT,

    /** A refresh token turned up after it had been rotated away — it exists in two places. */
    REPLAY,

    /** Dropped from the device list by its owner. */
    REVOKED,

    /**
     * The password was reset or changed. Told apart from a manual revoke because it ends many
     * sessions at once, and because a reset is what somebody does when they suspect the account is
     * not only theirs any more.
     */
    PASSWORD_CHANGED
}
