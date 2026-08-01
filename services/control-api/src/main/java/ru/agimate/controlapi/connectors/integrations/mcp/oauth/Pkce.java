package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** PKCE (RFC 7636) with {@code S256} only — {@code plain} is never offered, even if a server allows it. */
@UtilityClass
public class Pkce {

    public static final String METHOD = "S256";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 32 random bytes base64url-encoded — 43 characters, the length RFC 7636 recommends. */
    public static String generateVerifier() {
        return URL_ENCODER.encodeToString(CryptoUtils.randomBytes(32));
    }

    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new ConnectorException("SHA-256 is not available");
        }
    }
}
