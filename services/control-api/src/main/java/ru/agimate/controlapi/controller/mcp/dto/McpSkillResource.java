package ru.agimate.controlapi.controller.mcp.dto;

/**
 * One file of a skill manifest: the exact bytes the server will serve, named by their SHA-256 and
 * size. The digest is consistency with the manifest, not trust in the content.
 */
public record McpSkillResource(String uri, String digest, long size) {
}
