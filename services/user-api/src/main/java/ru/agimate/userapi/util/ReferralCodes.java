package ru.agimate.userapi.util;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;

/**
 * Referral codes: eight characters of Crockford base32, an alphabet that drops I, L, O and U —
 * the four that get misread when a link is copied off a slide or read aloud. Forty random bits,
 * so the code says nothing about who owns it.
 */
@UtilityClass
public class ReferralCodes {

    public static final int LENGTH = 8;

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
