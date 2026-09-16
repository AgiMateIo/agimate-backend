package ru.agimate.controlapi.connectors.core.annotation;

/**
 * Who may call a tool ({@link Tool#visibility()}) — the MCP Apps {@code visibility} values, so an internal
 * tool is declared in the same words an external server uses in {@code _meta.ui.visibility}.
 */
public enum ToolVisibility {
    /** The agent's model sees the tool in its list and calls it. */
    MODEL,
    /** A view of the same connection calls it on the agent's behalf. */
    APP;

    /** The wire value of MCP Apps. */
    public String wireValue() {
        return name().toLowerCase();
    }
}
