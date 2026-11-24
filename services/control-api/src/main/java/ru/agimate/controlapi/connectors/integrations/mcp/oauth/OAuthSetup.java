package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

/**
 * Everything discovery found out about how to authorise at one server. Produced when the connection
 * is created (and by a side-effect-free test call); re-authorisation reads the snapshot from the
 * secret. Persisted there, so neither the refresh job nor the token exchange has to walk the metadata
 * again.
 *
 * @param issuer   identity of the authorisation server; recorded before the redirect and compared
 *                 against {@code iss} on the way back (RFC 9207)
 * @param resource canonical identifier of the MCP server for RFC 8707; taken from protected resource
 *                 metadata, and only on the legacy path from the URL the user typed
 * @param scope    what to ask for, chosen by the spec's strategy; {@code null} means «send no scope
 *                 parameter at all», which is a legitimate outcome
 * @param client   the chosen client mechanism and what DCR needs to register a client
 */
public record OAuthSetup(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String resource,
        String scope,
        ClientIdentification client
) {}
