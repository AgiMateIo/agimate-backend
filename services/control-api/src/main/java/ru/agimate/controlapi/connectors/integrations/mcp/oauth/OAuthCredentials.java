package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OAuth half of a connection's secret. Keys are named {@code oauth_*} rather than {@code mcp_*}
 * deliberately: nothing here is protocol-specific, and when a second OAuth connector arrives the
 * format travels with it unchanged.
 *
 * <p>Everything lives in the encrypted map except the access token's expiry, which is a column —
 * inside the blob «who needs refreshing» could be answered neither by a query nor by the UI.
 */
@UtilityClass
public class OAuthCredentials {

    public static final String ISSUER = "oauth_issuer";
    public static final String AUTHORIZATION_ENDPOINT = "oauth_authorization_endpoint";
    public static final String TOKEN_ENDPOINT = "oauth_token_endpoint";
    public static final String RESOURCE = "oauth_resource";
    public static final String SCOPE_REQUESTED = "oauth_scope_requested";
    public static final String CODE_VERIFIER = "oauth_code_verifier";
    public static final String ACCESS_TOKEN = "oauth_access_token";
    public static final String REFRESH_TOKEN = "oauth_refresh_token";
    public static final String SCOPE_GRANTED = "oauth_scope_granted";

    /** What discovery produced, ready to be merged into the secret. */
    public static Map<String, String> of(OAuthSetup setup) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ISSUER, setup.issuer());
        values.put(AUTHORIZATION_ENDPOINT, setup.authorizationEndpoint());
        values.put(TOKEN_ENDPOINT, setup.tokenEndpoint());
        putIfPresent(values, RESOURCE, setup.resource());
        putIfPresent(values, SCOPE_REQUESTED, setup.scope());
        return values;
    }

    public static OAuthSetup setup(Map<String, String> credentials) {
        return new OAuthSetup(
                credentials.get(ISSUER),
                credentials.get(AUTHORIZATION_ENDPOINT),
                credentials.get(TOKEN_ENDPOINT),
                credentials.get(RESOURCE),
                credentials.get(SCOPE_REQUESTED));
    }

    /** Whether this connection authorises over OAuth at all (as opposed to a static token). */
    public static boolean isOAuth(Map<String, String> credentials) {
        String issuer = credentials.get(ISSUER);
        return issuer != null && !issuer.isBlank();
    }

    public static String accessToken(Map<String, String> credentials) {
        return credentials.get(ACCESS_TOKEN);
    }

    public static String refreshToken(Map<String, String> credentials) {
        return credentials.get(REFRESH_TOKEN);
    }

    /**
     * The secret rewritten with a fresh grant. A whole new map every time — {@code SecretService}
     * encrypts the full set of fields at once, so «append just the tokens» does not exist here.
     */
    public static Map<String, String> withTokens(Map<String, String> credentials, OAuthTokens tokens) {
        Map<String, String> updated = new LinkedHashMap<>(credentials);
        updated.put(ACCESS_TOKEN, tokens.accessToken());
        // A refresh token is not always re-issued on refresh; keeping the old one is correct, and
        // rotation (OAuth 2.1 requires it for public clients) replaces it when the server sends one.
        if (tokens.refreshToken() != null) {
            updated.put(REFRESH_TOKEN, tokens.refreshToken());
        }
        putIfPresent(updated, SCOPE_GRANTED, tokens.scope());
        updated.remove(CODE_VERIFIER);
        return updated;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }
}
