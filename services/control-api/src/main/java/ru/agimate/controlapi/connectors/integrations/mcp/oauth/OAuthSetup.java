package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

/**
 * Everything discovery found out about how to authorise at one server. Produced twice in a
 * connection's life — on creation and on re-authorisation — and persisted into the connection's
 * secret, so neither the refresh job nor the token exchange has to walk the metadata again.
 *
 * @param issuer   identity of the authorisation server; recorded before the redirect and compared
 *                 against {@code iss} on the way back (RFC 9207)
 * @param resource canonical identifier of the MCP server for RFC 8707; taken from protected resource
 *                 metadata, and only on the legacy path from the URL the user typed
 * @param scope    what to ask for, chosen by the spec's strategy; {@code null} means «send no scope
 *                 parameter at all», which is a legitimate outcome
 */
public record OAuthSetup(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String resource,
        String scope
) {}
