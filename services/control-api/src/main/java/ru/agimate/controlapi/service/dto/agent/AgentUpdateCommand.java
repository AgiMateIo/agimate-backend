package ru.agimate.controlapi.service.dto.agent;

import ru.agimate.controlapi.database.enums.AgentType;

/**
 * Service-layer контракт обновления агента — вход для {@code AgentService.update}, общий для HTTP-границы
 * (маппинг из {@code UpdateAgentRequest}) и коннекторного слоя.
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
