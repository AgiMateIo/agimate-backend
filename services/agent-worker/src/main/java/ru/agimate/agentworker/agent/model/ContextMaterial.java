package ru.agimate.agentworker.agent.model;

/**
 * What a tool result is to the run's context (progressive disclosure). Declared on the tool's spec
 * ({@code _meta}, see {@link ru.agimate.agentworker.agent.ToolRegistry#META_CONTEXT_MATERIAL}),
 * stamped on the result by the dispatcher and written to the turn ledger, where the backend's
 * history assembler reads it.
 */
public enum ContextMaterial {
    /** An ordinary result. */
    NONE,
    /** A {@code load_tools} delta: the raw output carries specs, the model sees the disclosed names. */
    TOOLS,
    /** A skill body ({@code load_skill}): handed to the model as is, exempt from the history cap. */
    SKILL;

    /** The {@code _meta} value naming this material; {@code null} for {@link #NONE}. */
    public static ContextMaterial fromMeta(String value) {
        if (value == null) {
            return NONE;
        }
        return switch (value) {
            case "tools" -> TOOLS;
            case "skill" -> SKILL;
            default -> NONE;
        };
    }
}
