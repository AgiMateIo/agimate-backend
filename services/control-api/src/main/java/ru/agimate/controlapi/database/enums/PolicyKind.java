package ru.agimate.controlapi.database.enums;

/**
 * What an {@code agent_connection_policies} rule refines: {@link #TOOL} — the arguments of a tool
 * call, {@link #TRIGGER} — the parameters of an incoming trigger. The single policy table is split
 * by this discriminator (it replaces the separate {@code agent_tool_policies}/{@code
 * agent_trigger_policies}).
 */
public enum PolicyKind {
    TOOL,
    TRIGGER
}
