package ru.agimate.controlapi.database.enums;

/** Lifecycle of a consent on the MCP server (docs/decisions/mcp-server-oauth.md). */
public enum McpOAuthGrantStatus {
    /** Consent given, the authorization code not exchanged yet; swept if it never is. */
    PENDING,
    ACTIVE,
    /** Revoked by the user, by the client, by a replayed code or refresh token, or by the agent's deletion. */
    REVOKED
}
