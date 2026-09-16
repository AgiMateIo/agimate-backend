package ru.agimate.controlapi.connectors.core.dto;

/** Whom a tool listing is for; see {@link ToolUi#visibleTo}. */
public enum ToolAudience {
    /** The agent's model — the run context, {@code /mcp}, {@code /agent/tools}. */
    MODEL,
    /** A view calling tools of its own connection. */
    VIEW,
    /** Service listings that call nothing themselves: the policy editor, the catalogue, channel validation. */
    ALL
}
