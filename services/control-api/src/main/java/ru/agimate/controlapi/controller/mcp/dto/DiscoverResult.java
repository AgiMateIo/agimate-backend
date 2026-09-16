package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code server/discover} — the revision's replacement for the {@code initialize}
 * handshake. The revision has no {@code serverInfo} field of its own: server identity travels in
 * {@code _meta} under {@code io.modelcontextprotocol/serverInfo}. {@code instructions} is omitted
 * until there is something to say in it. {@code ttlMs} and {@code cacheScope} are the revision's
 * {@code CacheableResult}, required here.
 *
 * @param cacheScope {@code "public"} or {@code "private"}
 */
public record DiscoverResult(
        List<String> supportedVersions,
        Map<String, Object> capabilities,
        long ttlMs,
        String cacheScope,
        @JsonProperty("_meta") Map<String, Object> meta
) implements McpResult {
}
