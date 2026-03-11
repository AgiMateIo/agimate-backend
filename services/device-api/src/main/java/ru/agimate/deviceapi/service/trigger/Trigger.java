package ru.agimate.deviceapi.service.trigger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Trigger(
        String connectorCode,
        String identity,
        String id,
        String name,
        Map<String, Object> data,
        String occurredAt
) {

    public static Trigger createBasic(String connectorCode, String identity, String name, Map<String, Object> data) {
        return new Trigger(
                connectorCode,
                identity,
                UUID.randomUUID().toString(),
                name,
                data,
                Instant.now().toString()
        );
    }
}
