package ru.agimate.connectorsapi.connector;

import java.util.Map;

public record ConnectorMethodParameter(
        String name,
        String displayName,
        String description,
        String type,
        boolean required,
        Object defaultValue,
        Map<String, Object> validation
) {
    public ConnectorMethodParameter(String name, String displayName, String description, String type, boolean required) {
        this(name, displayName, description, type, required, null, Map.of());
    }
}
