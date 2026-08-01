package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Optional;

/**
 * The MCP server refused the request for authorisation reasons: 401 (no or dead token) or 403 with
 * {@code error="insufficient_scope"} (the token is fine but too narrow). Carries the parsed
 * {@code Bearer} challenge, which is where both the metadata location and the required scope live.
 *
 * <p>Only these two statuses lead into discovery. A 400 or a 406 means the server did not understand
 * the request — a protocol revision mismatch, most likely — and treating any 4xx as «needs
 * authorisation» would show the user a login prompt for a broken handshake.
 */
public class McpUnauthorizedException extends ConnectorException {

    private final transient WwwAuthenticate challenge;
    private final boolean insufficientScope;

    public McpUnauthorizedException(String message, WwwAuthenticate challenge, boolean insufficientScope) {
        super(message);
        this.challenge = challenge;
        this.insufficientScope = insufficientScope;
    }

    public Optional<WwwAuthenticate> challenge() {
        return Optional.ofNullable(challenge);
    }

    /** 403 {@code insufficient_scope}: the fix is a step-up authorisation, not a new one. */
    public boolean insufficientScope() {
        return insufficientScope;
    }

    /** The scopes the server says the operation needs, if it said. */
    public Optional<String> requiredScope() {
        return challenge().flatMap(value -> value.parameter("scope"));
    }
}
