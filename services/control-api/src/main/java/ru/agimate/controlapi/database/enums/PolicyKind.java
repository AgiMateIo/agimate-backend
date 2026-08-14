package ru.agimate.controlapi.database.enums;

/**
 * What an {@code agent_connection_policies} rule refines: {@link #TOOL} — the arguments of a tool
 * call, {@link #TRIGGER} — the parameters of an incoming trigger. One table split by this
 * discriminator rather than one table per kind: the two hang off the same binding and differ only in
 * what {@code name} and {@code params_filter} address.
 */
public enum PolicyKind {
    TOOL,
    TRIGGER
}
