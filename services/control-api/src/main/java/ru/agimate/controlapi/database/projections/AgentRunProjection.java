package ru.agimate.controlapi.database.projections;

import ru.agimate.controlapi.database.enums.RunStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A row of the per-agent trigger listing: the trigger event (from {@code trigger_logs}) plus that
 * event's run for a particular agent (from {@code agent_runs}). {@code id} is the run's id
 * ({@code agent_runs.id}, i.e. run_id).
 */
public interface AgentRunProjection {
    UUID getId();
    UUID getTriggerLogId();
    String getConnectorCode();
    String getConnectionId();
    String getExternalId();
    String getName();
    LocalDateTime getOccurredAt();
    Map<String, Object> getInput();
    RunStatus getStatus();
    String getResult();
    String getError();
    UUID getSessionId();
    LocalDateTime getLastActivityAt();
    LocalDateTime getCreatedAt();
}
