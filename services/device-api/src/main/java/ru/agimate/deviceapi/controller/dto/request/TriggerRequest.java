package ru.agimate.deviceapi.controller.dto.request;


import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record TriggerRequest(
        String id,
        String type,
        @NotNull
        String name,
        String source,
        String deviceId,
        String userId,
        Instant occurredAt,
        @NotNull
        JsonNode data
) {
}
