package ru.agimate.controlapi.controller.mcp.dto;

/**
 * One entry of {@code resources/list}: a skill's {@code SKILL.md} as a resource. Separate from
 * {@link McpSkillEntry} because the base Resources shape has no manifest, only a resource record.
 */
public record McpResource(
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        Long size
) {
}
