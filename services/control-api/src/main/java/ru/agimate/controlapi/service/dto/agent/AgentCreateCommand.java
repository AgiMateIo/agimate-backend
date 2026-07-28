package ru.agimate.controlapi.service.dto.agent;

import ru.agimate.controlapi.database.enums.AgentType;

import java.util.List;
import java.util.UUID;

/**
 * The service-layer contract for creating an agent — the input of {@code AgentService.create}, shared
 * by the HTTP boundary (mapping from {@code CreateAgentRequest}) and the connector layer (the platform
 * connector), so the latter does not depend on {@code controller/**}.
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
