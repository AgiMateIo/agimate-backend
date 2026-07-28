package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.database.entities.Agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which of the bound agents the trigger is addressed to: {@code actorAgentId} is excluded — we do not
 * notify the initiator of its own action — and a non-empty {@code targetAgentIds} keeps only those
 * listed.
 * <p>
 * A limitation worth knowing before relying on this mechanism: the audience comes only from
 * {@link TriggerContext}, and that is filled in by whoever raised the trigger inside the process —
 * the board, time, memory, webchat, ACP. A trigger arriving from outside (an integration's webhook)
 * has no context: there is nothing to narrow the recipients with, and the selection is left entirely
 * to ABAC. The «actor + targets» pair itself is taken from the board's model — the action's author and
 * the task's participants — and may not fit other kinds of trigger.
 */
public record TriggerAudience(
        UUID actorAgentId,
        List<UUID> targetAgentIds
) {

    /**
     * Narrows the agent list to the audience: excludes the actor and (when given) keeps only the
     * targets. A null audience → the list unchanged.
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
