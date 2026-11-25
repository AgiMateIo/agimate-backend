package ru.agimate.controlapi.controller.mcp.dto;

import java.util.List;

/**
 * Result of {@code resources/read}. {@code ttlMs} is zero and {@code cacheScope} private: the bytes
 * are a live skill body, not a static document.
 */
public record ResourceReadResult(
        List<ResourceContent> contents,
        long ttlMs,
        String cacheScope
) implements McpResult {
}
