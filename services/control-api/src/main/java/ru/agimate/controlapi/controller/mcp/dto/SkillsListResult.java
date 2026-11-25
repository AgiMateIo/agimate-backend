package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code skills/list}. {@code ttlMs} and {@code cacheScope} are the revision's
 * {@code CacheableResult}; the listing is private and immediately stale because bindings and skill
 * bodies change without a {@code list_changed} notification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillsListResult(
        List<McpSkillEntry> skills,
        String nextCursor,
        long ttlMs,
        String cacheScope
) implements McpResult {
}
