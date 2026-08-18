package ru.agimate.controlapi.service.webchat;

import lombok.experimental.UtilityClass;

/** One-line previews of messages: the listings and the badge event cut them the same way. */
@UtilityClass
public class WebchatPreviews {

    /** An agent's answer can be kilobytes long, and a listing row shows one line of it. */
    static final int MAX_LENGTH = 160;

    public static String shorten(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
