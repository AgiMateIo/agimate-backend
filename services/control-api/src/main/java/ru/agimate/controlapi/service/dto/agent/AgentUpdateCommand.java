package ru.agimate.controlapi.service.dto.agent;

import ru.agimate.controlapi.database.enums.AgentType;

/**
 * The service-layer contract for updating an agent — the input of {@code AgentService.update}, shared
 * by the HTTP boundary (mapping from {@code UpdateAgentRequest}) and the connector layer.
 */
public record AgentUpdateCommand(
        String name,
        String description,
        String instructions,
        AgentType type,
        String webhookUrl,
        String webhookAuthHeader,
        Boolean enabled
) {
}
