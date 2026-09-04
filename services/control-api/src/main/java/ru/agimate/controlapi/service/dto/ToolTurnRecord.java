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

    public record Call(String id, String name, String argumentsJson) {

        /** From a JSONB row of {@code agent_run_turns.tool_calls} — the shape {@code AgentRunTurnService.save} writes. */
        public static Call fromRow(Map<String, Object> row) {
            return new Call(string(row, "id"), string(row, "name"), string(row, "argumentsJson"));
        }
    }

    public record Result(String id, String name, String outputJson, boolean failed) {

        /** From a JSONB row of {@code agent_run_turns.tool_results}. */
        public static Result fromRow(Map<String, Object> row) {
            return new Result(string(row, "id"), string(row, "name"), string(row, "outputJson"),
                    Boolean.TRUE.equals(row.get("failed")));
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
