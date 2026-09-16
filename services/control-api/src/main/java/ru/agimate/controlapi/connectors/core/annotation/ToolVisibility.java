package ru.agimate.controlapi.connectors.core.annotation;

/**
 * Who may call a tool ({@link Tool#visibility()}) — the MCP Apps {@code visibility} axis. On the wire the
 * spec's words ({@link #wireValue()}); in code {@code VIEW}, since «app» here already names the programs
 * on devices.
 */
public enum ToolVisibility {
    /** The agent's model sees the tool in its list and calls it. */
    MODEL,
    /** A view of the same connection calls it on the agent's behalf. */
    VIEW;

    /** The value in MCP Apps {@code _meta.ui.visibility}. */
    public String wireValue() {
        return switch (this) {
            case MODEL -> "model";
            case VIEW -> "app";
        };
    }
}
