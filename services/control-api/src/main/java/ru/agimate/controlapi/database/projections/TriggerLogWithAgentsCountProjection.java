package ru.agimate.controlapi.database.projections;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public interface TriggerLogWithAgentsCountProjection {
    UUID getId();
    String getConnectorCode();
    String getConnectionId();
    String getExternalId();
    String getName();
    LocalDateTime getOccurredAt();
    Map<String, Object> getInput();
    LocalDateTime getCreatedAt();
    long getAgentsCount();
}
