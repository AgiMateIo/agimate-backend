package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code server/discover} — the revision's replacement for the {@code initialize}
 * handshake. The revision has no {@code serverInfo} field of its own: server identity travels in
 * {@code _meta} under {@code io.modelcontextprotocol/serverInfo}. {@code instructions} is omitted
 * until there is something to say in it.
 */
public record DiscoverResult(
        List<String> supportedVersions,
        Map<String, Object> capabilities,
        @JsonProperty("_meta") Map<String, Object> meta
) implements McpResult {
}
