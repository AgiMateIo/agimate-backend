package ru.agimate.controlapi.service.trigger;

import java.util.UUID;

/**
 * A run of a conversation has just finished with its answer (DONE) — the point where the session's
 * upkeep is decided (docs/decisions/context-compaction.md). Published inside the message log's
 * transaction: listen after commit.
 *
 * @param sessionId the conversation's session, the one the history window is assembled for
 */
public record RunFinished(UUID runId, UUID agentId, UUID userId, UUID sessionId) {
}
