package ru.agimate.controlapi.controller.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * One skill in {@code skills/list}: the identity and frontmatter a host uses to decide, plus the
 * complete manifest of what it will serve. The body is not part of the entry.
 */
public record McpSkillEntry(
        String uri,
        Map<String, Object> frontmatter,
        List<McpSkillResource> resources
) {
}
