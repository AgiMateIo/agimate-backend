package ru.agimate.controlapi.realtime.dto;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code session.tool_call} in {@code user:{userId}}: which calls of a session finished.
 * The ids are the ones a delivered {@code TOOL_CALL} line carries; the view fetches their results
 * from {@code GET /manage/sessions/{id}/tool-calls/{toolCallId}}.
 */
public record SessionToolCallPayload(UUID sessionId, List<String> toolCallIds) {
}
