package ru.agimate.userapi.service.push;

import lombok.experimental.UtilityClass;

/** A push token is the right to notify a device, so it is never logged whole. */
@UtilityClass
public class PushTokens {

    private static final int VISIBLE_PREFIX = 8;

    static String masked(String token) {
        if (token == null || token.length() <= VISIBLE_PREFIX) {
            return "…";
        }
        return token.substring(0, VISIBLE_PREFIX) + "…";
    }
}
