package ru.agimate.controlapi.controller.app.dto;


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
        Instant occurredAt,
        @NotNull
        JsonNode data
) {
}
