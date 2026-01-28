package ru.agimate.connectorsapi.connector;

import java.util.List;

public record ConnectorMethod(
        String name,
        String displayName,
        String description,
        String httpMethod,
        String endpoint,
        ConnectorMethodCategory category,
        List<ConnectorMethodParameter> parameters
) {}
