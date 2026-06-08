package ru.agimate.controlapi.service.dto;

public record AgentToolSpec(
        String name,
        String description,
        Object parametersJsonSchema
) {
}
