package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * The client a request is made as. CIMD has no secret and authenticates as {@code none}; DCR usually
 * has one and authenticates with it.
 *
 * <p>{@link #toString()} redacts the secret: this record travels through logs and exception messages,
 * and the registration response is the one place a secret enters the system. {@code @JsonIgnore} on
 * the component keeps a record serialised by Jackson from walking around that redaction.
 */
public record ClientIdentity(
        OAuthClientSource source,
        String clientId,
        @JsonIgnore String clientSecret,
        TokenEndpointAuthMethod tokenEndpointAuthMethod
) {

    public ClientIdentity {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(tokenEndpointAuthMethod, "tokenEndpointAuthMethod");
    }

    public boolean hasSecret() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public String toString() {
        return "ClientIdentity[source=" + source
                + ", clientId=" + clientId
                + ", clientSecret=" + (hasSecret() ? "<redacted>" : "null")
                + ", tokenEndpointAuthMethod=" + tokenEndpointAuthMethod + "]";
    }
}
