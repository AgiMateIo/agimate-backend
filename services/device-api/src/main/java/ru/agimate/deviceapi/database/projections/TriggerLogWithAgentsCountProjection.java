package ru.agimate.deviceapi.database.projections;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public interface TriggerLogWithAgentsCountProjection {
    UUID getId();
    String getConnectorCode();
    String getIdentity();
    String getTriggerId();
    String getTriggerName();
    LocalDateTime getOccurredAt();
    Map<String, Object> getTriggerInput();
    LocalDateTime getCreatedAt();
    long getAgentsCount();
}
