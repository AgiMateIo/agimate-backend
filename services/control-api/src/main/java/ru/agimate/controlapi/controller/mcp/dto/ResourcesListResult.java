package ru.agimate.controlapi.controller.mcp.dto;

import java.util.List;

/**
 * Result of {@code resources/list}. One page: the number of resources equals the number of skills
 * the agent may use, and the skill catalogue already bounds that.
 */
public record ResourcesListResult(
        List<McpResource> resources,
        long ttlMs,
        String cacheScope
) implements McpResult {
}
