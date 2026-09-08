package ru.agimate.agentworker;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one rule for a tool's LLM-facing name, shared by control-api (which computes
 * {@code ConnectorToolSpec.llm_name}) and the worker (which falls back to it against an older
 * control-api). The same name must stand in the run's tool listing, in {@code describe_tools}
 * arguments and in the turn ledger — a one-character drift between the two sides would break the
 * history match, so the code is shared rather than the description of it.
 */
public final class ToolNames {

    private static final Pattern UNSAFE_NAME_CHAR = Pattern.compile("[^A-Za-z0-9_-]");

    private ToolNames() {
    }

    /**
     * Make {@code {namespace}.{name}} safe for the OpenAI function-calling name field. Dots become
     * {@code __} so a namespaced name ({@code board.get_tasks}) does not collide with an underscored
     * one ({@code board_get_tasks}); any remaining unsafe character maps to {@code _}.
     */
    public static String sanitize(String fullName) {
        return UNSAFE_NAME_CHAR.matcher(fullName.replace(".", "__")).replaceAll("_");
    }

    /**
     * Sanitizing can collide ({@code ns.a.b} vs namespace {@code ns.a} + tool {@code b} both give
     * {@code ns__a__b}); a silent overwrite would dispatch one tool into another's backend. The
     * later duplicate gets a numeric suffix — deterministic as long as both sides walk the specs in
     * the backend's order.
     */
    public static String unique(Set<String> taken, String sanitized) {
        if (!taken.contains(sanitized)) {
            return sanitized;
        }
        int n = 2;
        while (taken.contains(sanitized + "_" + n)) {
            n++;
        }
        return sanitized + "_" + n;
    }
}
