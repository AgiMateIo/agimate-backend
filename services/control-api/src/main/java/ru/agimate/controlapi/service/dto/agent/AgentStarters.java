package ru.agimate.controlapi.service.dto.agent;

import java.util.List;
import java.util.UUID;

/**
 * What an empty chat with the agent shows: the greeting and the visible starters, the agent's own first.
 *
 * @param greeting {@code null} — none
 */
public record AgentStarters(String greeting, List<Starter> starters) {

    /**
     * One starter. The agent's own has {@code id} and no {@code agentSkillId}; a skill's is addressed by
     * {@code agentSkillId} + {@code text}, as it has no row until hidden.
     */
    public record Starter(UUID id, UUID agentSkillId, String skillTitle, String text) {
    }

    /** An entry of the agent's own list on save: {@code id} {@code null} — a new starter. */
    public record OwnStarter(UUID id, String text) {
    }
}
