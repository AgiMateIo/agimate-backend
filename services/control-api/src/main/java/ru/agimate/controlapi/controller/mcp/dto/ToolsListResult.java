package ru.agimate.controlapi.controller.mcp.dto;

import java.util.List;

/**
 * Result of {@code tools/list}. No {@code nextCursor}: the listing is one agent's bindings, small by
 * construction, and pagination would only add a cursor to keep across a stateless protocol.
 * {@code ttlMs} and {@code cacheScope} are the revision's {@code CacheableResult}, required here.
 *
 * @param cacheScope {@code "public"} or {@code "private"}
 */
public record ToolsListResult(List<McpTool> tools, long ttlMs, String cacheScope) implements McpResult {
}
