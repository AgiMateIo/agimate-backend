package ru.agimate.controlapi.service.mcp.oauth;

import ru.agimate.controlapi.database.enums.McpOAuthClientKind;

import java.util.List;

/**
 * A client of the MCP server's authorization server as far as consent is concerned.
 *
 * @param host the host of a CIMD {@code client_id}: the one part of the identity the client cannot
 *             choose for itself, shown next to the self-declared name
 */
public record OAuthClient(String clientId, McpOAuthClientKind kind, String name, String host, List<String> redirectUris) {}
