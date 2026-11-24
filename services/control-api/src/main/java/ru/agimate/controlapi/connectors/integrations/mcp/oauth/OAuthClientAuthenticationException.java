package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import ru.agimate.controlapi.connectors.core.ConnectorException;

/**
 * The authorisation server refused the client itself — {@code invalid_client}, or a {@code 401} on a
 * request that authenticated with basic/post. Recoverable only by registering a new client, never by
 * retrying the same one.
 */
public class OAuthClientAuthenticationException extends ConnectorException {

    public OAuthClientAuthenticationException(String message) {
        super(message);
    }
}
