package ru.agimate.deviceapi.service.dto;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String eventId,
        String agentId,
        String eventType,
        Instant occurredAt,
        Map<String, Object> data) {
}
