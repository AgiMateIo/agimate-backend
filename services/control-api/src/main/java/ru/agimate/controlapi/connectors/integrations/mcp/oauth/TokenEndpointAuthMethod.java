package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

/**
 * How the client authenticates at the token endpoint, in the wire names of RFC 7591. Only the three
 * methods the connector can actually perform are represented; anything else in a registration
 * response is a refusal, not an invitation to guess.
 */
public enum TokenEndpointAuthMethod {
    CLIENT_SECRET_BASIC("client_secret_basic"),
    CLIENT_SECRET_POST("client_secret_post"),
    NONE("none");

    private final String wireName;

    TokenEndpointAuthMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Null for absent or unknown: the caller decides whether that is «use the requested one» or a refusal. */
    public static TokenEndpointAuthMethod from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TokenEndpointAuthMethod method : values()) {
            if (method.wireName.equals(value)) {
                return method;
            }
        }
        return null;
    }
}
