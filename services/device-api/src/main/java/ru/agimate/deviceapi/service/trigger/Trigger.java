package ru.agimate.deviceapi.service.trigger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Trigger(
        String connectorCode,
        String identity,
        String name,
        String id,
        Map<String, Object> data,
        String occurredAt,
        TriggerAudience audience
) {

    public static Trigger createBasic(String connectorCode, String identity, String name, Map<String, Object> data) {
        return new Trigger(
                connectorCode,
                identity,
                name, UUID.randomUUID().toString(),
                data,
                Instant.now().toString(),
                null
        );
    }

    public static Trigger createWithAudience(String connectorCode, String identity, String name,
                                             Map<String, Object> data, TriggerAudience audience) {
        return new Trigger(
                connectorCode,
                identity,
                name, UUID.randomUUID().toString(),
                data,
                Instant.now().toString(),
                audience
        );
    }
}
