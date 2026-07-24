package ru.agimate.controlapi.service.dto.agent;

import ru.agimate.controlapi.database.enums.AgentType;

import java.util.List;
import java.util.UUID;

/**
 * Service-layer контракт создания агента — вход для {@code AgentService.create}, общий для HTTP-границы
 * (маппинг из {@code CreateAgentRequest}) и коннекторного слоя (platform-коннектор), чтобы последний
 * не зависел от {@code controller/**}.
 */
public record AgentCreateCommand(
        String name,
        String description,
        String instructions,
        AgentType type,
        String webhookUrl,
        String webhookAuthHeader,
        UUID agenticTeamId,
        List<UUID> skillIds,
        String presetName
) {
}
