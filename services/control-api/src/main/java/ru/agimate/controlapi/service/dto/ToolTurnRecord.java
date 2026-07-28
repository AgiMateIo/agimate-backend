package ru.agimate.controlapi.service.dto;

import java.util.List;

/**
 * The structural record of a single assistant tool turn (protocol v2.1): preamble + calls + results.
 * Persisted into {@code channel_session_messages.message_json} on PROGRESS/TOOL_CALL and handed to the
 * history of later runs as native tool_use/tool_result — the textual 🔧 projection stays channel-only.
 * The field schema mirrors the worker's AgentChatMessage.
 */
public record ToolTurnRecord(String text, List<Call> calls, List<Result> results) {

    public record Call(String id, String name, String argumentsJson) {}

    public record Result(String id, String name, String outputJson, boolean failed) {}

    public ToolTurnRecord {
        calls = calls != null ? calls : List.of();
        results = results != null ? results : List.of();
    }

    public boolean isEmpty() {
        return calls.isEmpty() && results.isEmpty();
    }
}
