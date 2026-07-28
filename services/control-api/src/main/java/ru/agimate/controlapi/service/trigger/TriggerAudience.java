package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.database.entities.Agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Кому из привязанных агентов адресован триггер: {@code actorAgentId} исключается — инициатора не
 * уведомляем о его же действии, — а непустой {@code targetAgentIds} оставляет только перечисленных.
 * <p>
 * Ограничение, о котором стоит знать до того, как опираться на этот механизм: audience приходит
 * только из {@link TriggerContext}, а его заполняет тот, кто поднял триггер внутри процесса —
 * доска, time, память, webchat, ACP. У триггера, пришедшего снаружи (вебхук интеграции), контекста
 * нет: сузить получателей нечем, и отбор остаётся целиком за ABAC. Сама пара «actor + targets»
 * снята с модели доски — автор действия и участники задачи — и на других типах триггеров может
 * не лечь.
 */
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
