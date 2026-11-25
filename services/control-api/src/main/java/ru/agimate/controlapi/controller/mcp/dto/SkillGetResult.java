package ru.agimate.controlapi.controller.mcp.dto;

/**
 * Result of {@code skills/get}: the same entry {@code skills/list} returns, for a URI the host
 * already knows. Cacheable like the listing.
 */
public record SkillGetResult(
        McpSkillEntry skill,
        long ttlMs,
        String cacheScope
) implements McpResult {
}
