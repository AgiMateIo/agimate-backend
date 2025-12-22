package ru.agimate.mobileapi.controller.dto.request;


import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record TriggerRequest(
        String id,
        String type,
        String name,
        String source,
        String deviceId,
        String userId,
        Instant occurredAt,
        JsonNode data
) {
}
