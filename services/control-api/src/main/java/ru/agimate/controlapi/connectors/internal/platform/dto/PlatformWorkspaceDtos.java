package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;

/**
 * View models of the workspace tools of the platform connector ({@code PlatformWorkspaceToolService}):
 * agentic teams, presets, boards and connector jobs. Flat and LLM-friendly (public ids as strings).
 * See {@link PlatformDtos} for the shared-file rules; {@link PlatformDtos.AgentBrief} (used by the
 * team detail) lives in the shared file.
 *
 * <p>Lists are wrapped in an object — the top level of an MCP result is always an object.
 */
public final class PlatformWorkspaceDtos {

    private PlatformWorkspaceDtos() {
    }

    // ---- teams ----

    public record TeamList(List<TeamBrief> items, boolean truncated) {
    }

    public record TeamBrief(String id, String name, String description) {
    }

    /** The team plus its roster: every agent of the user placed into the team ({@code AgentBrief}). */
    public record TeamDetail(String id, String name, String description,
                             List<PlatformDtos.AgentBrief> members) {
    }

    // ---- presets ----

    public record PresetList(List<PresetBrief> items) {
    }

    public record PresetBrief(String id, String name, String title, String description,
                              List<String> skillNames, String agentType, int sortOrder,
                              boolean enabled) {
    }

    // ---- boards ----

    public record BoardList(List<BoardBrief> items, boolean truncated) {
    }

    public record BoardBrief(String id, String name, String description, String teamId) {
    }

    // ---- connector jobs ----

    public record ConnectorJobList(List<ConnectorJobItem> items, boolean truncated) {
    }

    public record ConnectorJobItem(String id, String kind, String connectorCode, String connectionId,
                                   String name, String type, String status, String nextRunAt,
                                   String pausedAt, String lastError) {
    }
}
