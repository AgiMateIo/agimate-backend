package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.database.entities.Agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// TODO: пересмотреть это решение с Audience и сделать его универасальным для всех типов триггеров и сообщений для всех типов агентов.
public record TriggerAudience(
        UUID actorAgentId,
        List<UUID> targetAgentIds
) {

    /**
     * Сужает список агентов под audience: исключает actor и (если задан) оставляет только targets.
     * null audience → список без изменений.
     */
    public static List<Agent> filter(List<Agent> agents, TriggerAudience audience) {
        if (audience == null) {
            return agents;
        }
        if (audience.actorAgentId() != null) {
            agents = agents.stream()
                    .filter(a -> !a.getId().equals(audience.actorAgentId()))
                    .toList();
        }
        if (audience.targetAgentIds() != null && !audience.targetAgentIds().isEmpty()) {
            Set<UUID> allowed = Set.copyOf(audience.targetAgentIds());
            agents = agents.stream()
                    .filter(a -> allowed.contains(a.getId()))
                    .toList();
        }
        return agents;
    }
}
