package ru.agimate.controlapi.service.dto;

import java.util.List;

/**
 * Структурная запись одного tool-хода ассистента (протокол v2.1): преамбула + вызовы +
 * результаты. Персистится в {@code channel_session_messages.message_json} у PROGRESS/TOOL_CALL
 * и отдаётся истории следующих ранов как нативные tool_use/tool_result — текстовая 🔧-проекция
 * остаётся только канальной. Схема полей повторяет AgentChatMessage воркера.
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
