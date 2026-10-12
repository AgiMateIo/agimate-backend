package ru.agimate.controlapi.database.enums;

/** How an OAuth client of the MCP server identified itself. */
public enum McpOAuthClientKind {
    /** {@code client_id} is the HTTPS address of its own metadata document. */
    CIMD,
    /** Registered through Dynamic Client Registration; {@code client_id} is ours. */
    DCR
}
