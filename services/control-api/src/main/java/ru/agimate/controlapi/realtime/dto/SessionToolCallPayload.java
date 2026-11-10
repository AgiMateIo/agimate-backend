package ru.agimate.controlapi.realtime.dto;

import ru.agimate.controlapi.controller.manage.dto.session.ToolCallDescriptor;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code session.tool_call} in {@code user:{userId}}: which calls of a session finished and,
 * for the ones the journal knows, the view each renders into. Ids without a journal row are absent
 * from {@code toolCalls}; {@code null} — the descriptors were not resolved and the client uses the
 * per-call endpoint.
 */
public record SessionToolCallPayload(UUID sessionId, List<String> toolCallIds,
                                     List<ToolCallDescriptor> toolCalls) {
}
