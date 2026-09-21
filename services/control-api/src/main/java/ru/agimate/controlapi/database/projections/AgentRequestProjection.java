package ru.agimate.controlapi.database.projections;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A request one agent handed to another: the callee's thread ({@code agent_sessions} of the
 * {@code agents} connector) joined with the conversation it works for and the callee's team. What
 * happened in the thread is not here — that is folded from its runs.
 */
public interface AgentRequestProjection {
    UUID getId();
    UUID getUserId();
    UUID getTeamId();
    String getTitle();
    UUID getToAgentId();
    UUID getFromAgentId();
    UUID getOriginSessionId();
    String getOriginTitle();
    String getOriginConnectorCode();
    LocalDateTime getCreatedAt();
    LocalDateTime getLastActivityAt();
    LocalDateTime getClosedAt();
}
