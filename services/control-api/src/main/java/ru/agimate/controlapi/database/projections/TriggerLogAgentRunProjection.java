package ru.agimate.controlapi.database.projections;

import ru.agimate.controlapi.database.enums.RunStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Строка per-agent trigger-листинга: событие триггера (из {@code trigger_logs}) + прогон этого
 * события у конкретного агента (из {@code trigger_log_agents}). {@code id} — id прогона
 * ({@code trigger_log_agents.id}, он же run_id).
 */
public interface TriggerLogAgentRunProjection {
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
