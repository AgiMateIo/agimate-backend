package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import ru.agimate.controlapi.connectors.core.ConnectorException;

/**
 * The grant itself is dead — {@code invalid_grant}: revoked, expired, or already used. The one class
 * of failure that means «only the user can fix this»; a timeout or a 5xx from the authorisation
 * server is a retry, and treating those the same way would push a live connection into
 * «go re-authorise» because the network blinked once.
 */
public class OAuthGrantRejectedException extends ConnectorException {

    public OAuthGrantRejectedException(String message) {
        super(message);
    }
}
