package ru.agimate.controlapi.database.enums;

/** What a client presents to the MCP server's authorization server. */
public enum McpOAuthTokenKind {
    CODE,
    ACCESS,
    REFRESH
}
