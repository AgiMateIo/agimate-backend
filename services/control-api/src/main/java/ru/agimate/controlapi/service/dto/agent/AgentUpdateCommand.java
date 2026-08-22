package ru.agimate.controlapi.service.dto.agent;

import ru.agimate.controlapi.database.enums.AgentType;

/**
 * The service-layer contract for updating an agent, shared by the HTTP boundary and the connector layer.
 *
 * <p>The same record feeds two readings, and the method picked decides which: {@code AgentService.update}
 * writes every field as given (PUT), {@code AgentService.patch} treats {@code null} as "not sent" and a
 * blank string as an erase. One record rather than two identical ones — the semantics live on the method,
 * where the caller already has to choose.
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
