package ru.agimate.controlapi.controller.mcp.dto;

import java.util.Map;

/**
 * Result of {@code initialize}. Capabilities are a map rather than a type: this server advertises
 * exactly one ({@code tools}), and a record per empty settings object would be shape without content.
 */
public record InitializeResult(
        String protocolVersion,
        Map<String, Object> capabilities,
        ServerInfo serverInfo
) {

    public record ServerInfo(String name, String version) {}
}
