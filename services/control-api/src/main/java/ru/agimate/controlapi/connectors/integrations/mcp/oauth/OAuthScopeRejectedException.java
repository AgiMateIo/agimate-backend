package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import ru.agimate.controlapi.connectors.core.ConnectorException;

/**
 * The authorisation server refused the scope, usually because the registered client is bounded by a
 * narrower one. The client is not dead, but reusing it would repeat the same refusal — the next
 * start registers a client for the current scope.
 */
public class OAuthScopeRejectedException extends ConnectorException {

    public OAuthScopeRejectedException(String message) {
        super(message);
    }
}
