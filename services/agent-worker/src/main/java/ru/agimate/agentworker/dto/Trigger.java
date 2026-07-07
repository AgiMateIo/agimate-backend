package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The event payload enqueued to the worker. Mirrors control-api's {@code Trigger}; the
 * producer-only routing {@code context} field is ignored here (swallowed by
 * {@code ignoreUnknown}). {@code data} is the raw event body, treated strictly as untrusted
 * data by the trigger path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Trigger(
        String connectorCode,
        String identity,
        String name,
        String id,
        Map<String, Object> data,
        String occurredAt
) {
    public Map<String, Object> data() {
        return data != null ? data : Map.of();
    }
}
