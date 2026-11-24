package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OAuth half of a connection's secret. Keys are named {@code oauth_*} rather than {@code mcp_*}
 * deliberately: nothing here is protocol-specific, and when a second OAuth connector arrives the
 * format travels with it unchanged.
 *
 * <p>Two client identities can coexist: the grant owns the client its refresh token was issued to,
 * the flow owns the client a pending authorisation was started with. They are the same object in the
 * ordinary case and differ exactly when the user reconnects — which is why they are separate keys
 * rather than one mutable row.
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

    /** Discovery's choice of mechanism; kept when the client itself is cleared, so DCR stays DCR. */
    public static final String CLIENT_SOURCE = "oauth_client_source";
    public static final String REGISTRATION_ENDPOINT = "oauth_registration_endpoint";
    public static final String TOKEN_ENDPOINT_AUTH_METHODS = "oauth_token_endpoint_auth_methods";
    public static final String CLIENT_ID = "oauth_client_id";
    public static final String CLIENT_SECRET = "oauth_client_secret";
    public static final String CLIENT_AUTH_METHOD = "oauth_client_auth_method";
    public static final String CLIENT_SCOPE = "oauth_client_scope";
    public static final String CLIENT_SECRET_EXPIRES_AT = "oauth_client_secret_expires_at";

    public static final String FLOW_CLIENT_SOURCE = "oauth_flow_client_source";
    public static final String FLOW_CLIENT_ID = "oauth_flow_client_id";
    public static final String FLOW_CLIENT_SECRET = "oauth_flow_client_secret";
    public static final String FLOW_CLIENT_AUTH_METHOD = "oauth_flow_client_auth_method";
    public static final String FLOW_CLIENT_SCOPE = "oauth_flow_client_scope";
    public static final String FLOW_CLIENT_SECRET_EXPIRES_AT = "oauth_flow_client_secret_expires_at";

    private static final String OFFLINE_ACCESS = "offline_access";

    /** What discovery produced, ready to be merged into the secret. */
    public static Map<String, String> of(OAuthSetup setup) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ISSUER, setup.issuer());
        values.put(AUTHORIZATION_ENDPOINT, setup.authorizationEndpoint());
        values.put(TOKEN_ENDPOINT, setup.tokenEndpoint());
        putIfPresent(values, RESOURCE, setup.resource());
        putIfPresent(values, SCOPE_REQUESTED, setup.scope());
        values.put(CLIENT_SOURCE, setup.client().source().name());
        putIfPresent(values, REGISTRATION_ENDPOINT, setup.client().registrationEndpoint());
        if (!setup.client().tokenEndpointAuthMethods().isEmpty()) {
            values.put(TOKEN_ENDPOINT_AUTH_METHODS,
                    String.join(" ", setup.client().tokenEndpointAuthMethods()));
        }
        return values;
    }

    public static OAuthSetup setup(Map<String, String> credentials) {
        return new OAuthSetup(
                credentials.get(ISSUER),
                credentials.get(AUTHORIZATION_ENDPOINT),
                credentials.get(TOKEN_ENDPOINT),
                credentials.get(RESOURCE),
                credentials.get(SCOPE_REQUESTED),
                new ClientIdentification(
                        OAuthClientSource.from(credentials.get(CLIENT_SOURCE)),
                        credentials.get(REGISTRATION_ENDPOINT),
                        List.copyOf(scopeSet(credentials.get(TOKEN_ENDPOINT_AUTH_METHODS)))));
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

    public static OAuthClientSource clientSource(Map<String, String> credentials) {
        return OAuthClientSource.from(credentials.get(CLIENT_SOURCE));
    }

    /** The client the grant's refresh token belongs to; null when the grant has none. */
    public static ClientIdentity grantClient(Map<String, String> credentials) {
        return identity(credentials, CLIENT_SOURCE, CLIENT_ID, CLIENT_SECRET, CLIENT_AUTH_METHOD);
    }

    /** The client a pending authorisation was started with; null when no flow client was recorded. */
    public static ClientIdentity flowClient(Map<String, String> credentials) {
        return identity(credentials, FLOW_CLIENT_SOURCE, FLOW_CLIENT_ID, FLOW_CLIENT_SECRET,
                FLOW_CLIENT_AUTH_METHOD);
    }

    private static ClientIdentity identity(Map<String, String> credentials, String sourceKey,
                                           String idKey, String secretKey, String methodKey) {
        String clientId = credentials.get(idKey);
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        OAuthClientSource source = OAuthClientSource.from(credentials.get(sourceKey));
        TokenEndpointAuthMethod method = TokenEndpointAuthMethod.from(credentials.get(methodKey));
        if (source == OAuthClientSource.CIMD) {
            method = TokenEndpointAuthMethod.NONE;
        }
        if (method == null) {
            return null;
        }
        return new ClientIdentity(source, clientId, credentials.get(secretKey), method);
    }

    public static String grantRegisteredScope(Map<String, String> credentials) {
        return credentials.get(CLIENT_SCOPE);
    }

    public static Long grantSecretExpiresAt(Map<String, String> credentials) {
        return secretExpiresAt(credentials.get(CLIENT_SECRET_EXPIRES_AT));
    }

    public static String flowRegisteredScope(Map<String, String> credentials) {
        return credentials.get(FLOW_CLIENT_SCOPE);
    }

    public static Long flowSecretExpiresAt(Map<String, String> credentials) {
        return secretExpiresAt(credentials.get(FLOW_CLIENT_SECRET_EXPIRES_AT));
    }

    private static Long secretExpiresAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(value);
            return epochSeconds > 0 ? epochSeconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Flow keys for a client selected for a new authorisation; the map is written as a whole. */
    public static Map<String, String> flowClientKeys(ClientIdentity identity, String registeredScope,
                                                     Long secretExpiresAt) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(FLOW_CLIENT_SOURCE, identity.source().name());
        values.put(FLOW_CLIENT_ID, identity.clientId());
        putIfPresent(values, FLOW_CLIENT_SECRET, identity.clientSecret());
        values.put(FLOW_CLIENT_AUTH_METHOD, identity.tokenEndpointAuthMethod().wireName());
        putIfPresent(values, FLOW_CLIENT_SCOPE, registeredScope);
        if (secretExpiresAt != null && secretExpiresAt > 0) {
            values.put(FLOW_CLIENT_SECRET_EXPIRES_AT, Long.toString(secretExpiresAt));
        }
        return values;
    }

    public static void clearFlowClient(Map<String, String> credentials) {
        credentials.keySet().removeIf(key -> key.startsWith("oauth_flow_client_"));
    }

    /** Removes the client credentials of the grant, keeping the mechanism marker. */
    public static void clearGrantClient(Map<String, String> credentials) {
        credentials.remove(CLIENT_ID);
        credentials.remove(CLIENT_SECRET);
        credentials.remove(CLIENT_AUTH_METHOD);
        credentials.remove(CLIENT_SCOPE);
        credentials.remove(CLIENT_SECRET_EXPIRES_AT);
    }

    /**
     * The credentials rewritten with a refreshed grant. A refresh does not touch the pending
     * authorisation: its verifier and flow client must survive, or a refresh in the middle of a
     * reconnect would invalidate the flow.
     */
    public static Map<String, String> withRefreshedTokens(Map<String, String> credentials, OAuthTokens tokens) {
        Map<String, String> updated = new LinkedHashMap<>(credentials);
        updated.put(ACCESS_TOKEN, tokens.accessToken());
        // A refresh token is not always re-issued on refresh; keeping the old one is correct, and
        // rotation (OAuth 2.1 requires it for public clients) replaces it when the server sends one.
        if (tokens.refreshToken() != null) {
            updated.put(REFRESH_TOKEN, tokens.refreshToken());
        }
        if (tokens.scope() != null) {
            updated.put(SCOPE_GRANTED, tokens.scope());
        }
        return updated;
    }

    /**
     * The credentials rewritten with a grant obtained by the user. The flow client becomes the
     * grant's client, and the old refresh token and granted scope do not survive: inheriting them
     * would attach a token issued to the old client to the new one.
     */
    public static Map<String, String> withGrant(Map<String, String> credentials, OAuthTokens tokens) {
        Map<String, String> updated = new LinkedHashMap<>(credentials);
        updated.put(ACCESS_TOKEN, tokens.accessToken());
        if (tokens.refreshToken() != null) {
            updated.put(REFRESH_TOKEN, tokens.refreshToken());
        } else {
            updated.remove(REFRESH_TOKEN);
        }
        if (tokens.scope() != null) {
            updated.put(SCOPE_GRANTED, tokens.scope());
        } else {
            updated.remove(SCOPE_GRANTED);
        }

        ClientIdentity flow = flowClient(credentials);
        if (flow != null) {
            updated.put(CLIENT_SOURCE, flow.source().name());
            updated.put(CLIENT_ID, flow.clientId());
            if (flow.hasSecret()) {
                updated.put(CLIENT_SECRET, flow.clientSecret());
            } else {
                updated.remove(CLIENT_SECRET);
            }
            updated.put(CLIENT_AUTH_METHOD, flow.tokenEndpointAuthMethod().wireName());
            copyFlowValue(credentials, updated, FLOW_CLIENT_SCOPE, CLIENT_SCOPE);
            copyFlowValue(credentials, updated, FLOW_CLIENT_SECRET_EXPIRES_AT, CLIENT_SECRET_EXPIRES_AT);
        }

        clearFlowClient(updated);
        updated.remove(CODE_VERIFIER);
        return updated;
    }

    private static void copyFlowValue(Map<String, String> from, Map<String, String> to,
                                      String flowKey, String grantKey) {
        String value = from.get(flowKey);
        if (value == null || value.isBlank()) {
            to.remove(grantKey);
        } else {
            to.put(grantKey, value);
        }
    }

    /** The scope a DCR client is registered with: the requested one without {@code offline_access}. */
    public static String registrationScope(String requestedScope) {
        Set<String> scopes = scopeSet(requestedScope);
        scopes.remove(OFFLINE_ACCESS);
        return scopes.isEmpty() ? null : String.join(" ", scopes);
    }

    /**
     * Drops {@code offline_access} from the requested scope after the server refused it. The next
     * authorisation then asks for the rest instead of re-registering a client for the same refusal.
     */
    public static void dropOfflineAccess(Map<String, String> credentials) {
        String requested = credentials.get(SCOPE_REQUESTED);
        if (requested == null) {
            return;
        }
        Set<String> scopes = scopeSet(requested);
        if (!scopes.remove(OFFLINE_ACCESS)) {
            return;
        }
        if (scopes.isEmpty()) {
            credentials.remove(SCOPE_REQUESTED);
        } else {
            credentials.put(SCOPE_REQUESTED, String.join(" ", scopes));
        }
    }

    /**
     * Whether a client registered with {@code registeredScope} can serve {@code requestedScope}.
     * An empty registration scope is «unknown», not «everything»: it is usable only when nothing is
     * requested, or the client could be reused until the server answers {@code invalid_scope}.
     */
    public static boolean scopeCovers(String registeredScope, String requestedScope) {
        Set<String> requested = scopeSet(requestedScope);
        requested.remove(OFFLINE_ACCESS);
        if (requested.isEmpty()) {
            return true;
        }
        Set<String> registered = scopeSet(registeredScope);
        return !registered.isEmpty() && registered.containsAll(requested);
    }

    private static Set<String> scopeSet(String scope) {
        if (scope == null || scope.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(List.of(scope.trim().split("\\s+")));
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }
}
