package ru.agimate.controlapi.connectors.internal.platform.dto;

/**
 * View models shared across the platform connector's tool-service modules — the return types of
 * their {@code @Tool} methods. Flat and LLM-friendly (public ids as strings), assembled by the
 * connector from the repositories. They live in the connector layer (not in {@code controller/**}):
 * records give the reflector a proper {@code outputSchema}, unlike {@code Map<String,Object>}. Lists
 * are wrapped in an object — the top level of an MCP result is always an object.
 *
 * <p>Only records used by two or more modules live here ({@link OperationResult} is returned by
 * every mutation tool; {@link AgentBrief} is used by the agent module's listing and the workspace
 * module's team detail). Module-specific records live in the per-module DTO files
 * ({@code PlatformAgentDtos}, {@code PlatformConnectionDtos}, …).
 */
public final class PlatformDtos {

    private PlatformDtos() {
    }

    public record AgentBrief(String id, String name, String description, String type,
                             boolean enabled, String teamId) {
    }

    public record OperationResult(boolean ok, String message) {
    }
}
