package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Также wire-payload для воркера в {@code AgentMessage}; {@code NON_NULL} убирает routing-поля (audience) когда пусты. */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
