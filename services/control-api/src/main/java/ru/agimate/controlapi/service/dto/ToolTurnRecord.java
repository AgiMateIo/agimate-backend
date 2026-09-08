package ru.agimate.controlapi.service.dto;

import java.util.List;
import java.util.Map;

/**
 * The structural record of a single assistant tool turn (protocol v2.1): preamble + calls + results.
 * Persisted into {@code channel_session_messages.message_json} on PROGRESS/TOOL_CALL and handed to the
 * history of later runs as native tool_use/tool_result — the textual 🔧 projection stays channel-only.
 * The field schema mirrors the worker's AgentChatMessage.
 */
public record ToolTurnRecord(String text, List<Call> calls, List<Result> results) {

    /**
     * What a result is to the run's context (progressive disclosure); stamped by the worker from the
     * tool's {@code _meta}, read by the history assembler. {@code null} — an ordinary result.
     */
    public enum Material {
        /** A {@code load_tools} delta: {@code outputJson} lists the disclosed tool names. */
        TOOLS,
        /** A skill body from {@code load_skill}: exempt from the tool-result cap in history. */
        SKILL
    }

    public record Call(String id, String name, String argumentsJson) {

        /** From a JSONB row of {@code agent_run_turns.tool_calls} — the shape {@code AgentRunTurnService.save} writes. */
        public static Call fromRow(Map<String, Object> row) {
            return new Call(string(row, "id"), string(row, "name"), string(row, "argumentsJson"));
        }
    }

    /** @param material {@code null} for an ordinary result — and for every row written before the axis existed */
    public record Result(String id, String name, String outputJson, boolean failed, Material material) {

        public Result(String id, String name, String outputJson, boolean failed) {
            this(id, name, outputJson, failed, null);
        }

        /** From a JSONB row of {@code agent_run_turns.tool_results}. */
        public static Result fromRow(Map<String, Object> row) {
            return new Result(string(row, "id"), string(row, "name"), string(row, "outputJson"),
                    Boolean.TRUE.equals(row.get("failed")), material(string(row, "material")));
        }

        private static Material material(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Material.valueOf(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    public ToolTurnRecord {
        calls = calls != null ? calls : List.of();
        results = results != null ? results : List.of();
    }

    public boolean isEmpty() {
        return calls.isEmpty() && results.isEmpty();
    }
}
