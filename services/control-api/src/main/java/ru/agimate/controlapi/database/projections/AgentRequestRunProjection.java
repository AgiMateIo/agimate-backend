package ru.agimate.controlapi.database.projections;

import ru.agimate.controlapi.database.enums.RunStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A run of a request thread, with the name of the trigger that raised it: {@code request_received}
 * for the request itself, {@code tool_completed} for a detached call coming back. The state of a
 * thread — working, answered, stopped — is folded from these rather than stored.
 */
public interface AgentRequestRunProjection {
    UUID getSessionId();
    UUID getId();
    String getName();
    RunStatus getStatus();
    LocalDateTime getSteeredAt();
    LocalDateTime getCancelRequestedAt();
    LocalDateTime getReportedAt();
    LocalDateTime getCreatedAt();
    LocalDateTime getLastActivityAt();
    LocalDateTime getUpdatedAt();
    String getResult();
    String getError();
}
