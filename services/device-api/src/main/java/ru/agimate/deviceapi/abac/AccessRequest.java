package ru.agimate.deviceapi.abac;

public record AccessRequest(
        String agentName,
        String connectorName,
        String connectorIdentity,
        String toolName
) {}
