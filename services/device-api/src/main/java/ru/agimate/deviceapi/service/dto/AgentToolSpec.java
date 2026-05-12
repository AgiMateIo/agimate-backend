package ru.agimate.deviceapi.service.dto;

public record AgentToolSpec(
        String name,
        String description,
        Object parametersJsonSchema
) {
}
