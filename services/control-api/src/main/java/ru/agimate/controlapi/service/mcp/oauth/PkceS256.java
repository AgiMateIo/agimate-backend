package ru.agimate.controlapi.service.mcp.oauth;

import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** The server half of PKCE (RFC 7636), {@code S256} only. */
@UtilityClass
public class PkceS256 {

    public static final String METHOD = "S256";

    /** Both the verifier and an S256 challenge are unreserved characters, 43 to 128 of them. */
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    public static boolean wellFormed(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    public static boolean matches(String verifier, String challenge) {
        if (!wellFormed(verifier) || !wellFormed(challenge)) {
            return false;
        }
        String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.US_ASCII), challenge.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
