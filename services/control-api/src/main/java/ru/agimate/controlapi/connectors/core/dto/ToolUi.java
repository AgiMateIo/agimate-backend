package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What we understand of a tool's MCP Apps metadata ({@code _meta.ui}): the view it renders into and
 * who may call it. Kept apart from {@link ConnectorToolSpec#meta()} on purpose — that map travels to
 * the worker and out through {@code /mcp}, while a {@code ui://} link is only meaningful to our own
 * host, which is the one able to serve it.
 *
 * @param resourceUri the {@code ui://} resource of the view; {@code null} — the tool has no view of its own
 * @param visibility  {@code "model"} and/or {@code "app"} as declared; {@code null} — not declared
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolUi(String resourceUri, List<String> visibility) {

    public static final String VISIBILITY_APP = "app";

    /**
     * Stricter than the spec, where an absent {@code visibility} means both: a view may call a tool
     * only when the server said so explicitly, since the call runs as the agent.
     */
    @JsonIgnore
    public boolean callableFromView() {
        return visibility != null && visibility.contains(VISIBILITY_APP);
    }
}
