package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.connectors.core.annotation.ToolVisibility;

import java.util.List;

/**
 * A tool's MCP Apps metadata ({@code _meta.ui}): the view it renders into and who may call it — as an
 * external server declared it, or as {@code BaseConnectorHandler} derives it from {@code @Tool}. Kept apart from {@link ConnectorToolSpec#meta()} on purpose — that map travels to
 * the worker and out through {@code /mcp}, while a {@code ui://} link is only meaningful to our own
 * host, which is the one able to serve it.
 *
 * @param resourceUri the {@code ui://} resource of the view; {@code null} — the tool has no view of its own
 * @param visibility  {@code "model"} and/or {@code "app"} as declared; {@code null} — not declared, see {@link #visibleTo}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolUi(String resourceUri, List<String> visibility) {

    /**
     * Absent {@code visibility} means both, as MCP Apps defines it: the author of an external server
     * wrote its tools for its own views. Internal tools always carry an explicit one, so our stricter
     * default lives on {@code @Tool}, not here. A tool with no {@code ui} at all is judged the same way.
     *
     * <p>An empty list hides the tool from both audiences, but only an internal {@code @Tool(visibility = {})}
     * also refuses execution: it is a job's dispatch target, while an external server still answers
     * {@code tools/call} for it.
     */
    public static boolean visibleTo(ToolUi ui, ToolAudience audience) {
        List<String> visibility = ui == null ? null : ui.visibility();
        return switch (audience) {
            case ALL -> true;
            case MODEL -> visibility == null || visibility.contains(ToolVisibility.MODEL.wireValue());
            case VIEW -> visibility == null || visibility.contains(ToolVisibility.VIEW.wireValue());
        };
    }
}
